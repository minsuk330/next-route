package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import watoo.grd.nextroute.application.journey.dto.BoardingEstimate;
import watoo.grd.nextroute.application.journey.dto.BoardingOutcome;
import watoo.grd.nextroute.application.journey.dto.BoardingQuery;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.port.out.BoardingTimeEstimator;
import watoo.grd.nextroute.application.journey.service.BoardingChainEtaCalculator.Budget;
import watoo.grd.nextroute.application.journey.service.BoardingChainEtaCalculator.Result;
import watoo.grd.nextroute.application.journey.service.BoardingChainEtaCalculator.Step;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * 경로 하나를 시계를 굴리며 목적지 도착 시각으로 옮기는 규칙.
 *
 * <p>여기서 못박는 것이 ④ 개선폭의 정직함을 떠받친다 — <b>모든 대기에 물어보고</b>, 못 풀면
 * 0으로 두는 대신 <b>후보를 버린다</b>. 예전 합산은 첫 대기만 물어보고 나머지를 0으로 뒀는데,
 * 구조가 다른 두 경로를 그렇게 견주면 빠진 대기 시간이 그대로 가짜 개선폭이 된다.
 */
@DisplayName("승차 체인 ETA")
class BoardingChainEtaCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-09-19T09:00:00Z");
    private static final Instant ANCHOR = NOW.plusSeconds(60);

    private final BoardingTimeEstimator busEstimator = mock(BoardingTimeEstimator.class);
    private final BoardingChainEtaCalculator calculator = new BoardingChainEtaCalculator(
            List.of(busEstimator), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("두 번째 대기의 앵커는 첫 차를 타고 간 뒤의 시각이다")
    void secondWaitIsAnchoredAfterTheFirstRide() {
        supports();
        // 첫 차: 앵커 +120초 승차. 두 번째: 물어본 앵커 그대로 탄다고 답한다.
        given(busEstimator.estimate(any(), any(), any(), anyLong()))
                .willAnswer(call -> boarding(call.getArgument(1, Instant.class).plusSeconds(120)));

        Result result = calculator.compute(chainOfTwoTransfers(), -1, ANCHOR, NOW, Budget.of(1000));

        ArgumentCaptor<Instant> anchors = ArgumentCaptor.forClass(Instant.class);
        verify(busEstimator, times(2)).estimate(any(BoardingQuery.class), anchors.capture(),
                any(), anyLong());
        // 첫 대기: 앵커 + 앞 도보 60.
        assertThat(anchors.getAllValues().get(0)).isEqualTo(ANCHOR.plusSeconds(60));
        // 두 번째 대기: 첫 승차(+120) + 주행 600 + 환승 도보 120.
        assertThat(anchors.getAllValues().get(1))
                .isEqualTo(ANCHOR.plusSeconds(60 + 120 + 600 + 120));
        // 목적지: 두 번째 승차(+120) + 주행 300.
        assertThat(result.destinationEtaAt())
                .isEqualTo(ANCHOR.plusSeconds(60 + 120 + 600 + 120 + 120 + 300));
        // 기다린 시간을 0으로 접지 않는다 — 두 번 다 120초씩 기다렸다.
        assertThat(result.waitingSec()).isEqualTo(240);
        assertThat(result.walkingSec()).isEqualTo(180);
        assertThat(result.boardings()).isEqualTo(2);
    }

    @Test
    @DisplayName("두 번째 대기를 못 풀면 후보를 버린다 — 0으로 두면 가짜 개선폭이 된다")
    void unresolvedSecondWaitDropsTheCandidate() {
        supports();
        given(busEstimator.estimate(any(), any(), any(), anyLong()))
                .willReturn(boarding(ANCHOR.plusSeconds(180)))
                .willReturn(BoardingOutcome.none());

        Result result = calculator.compute(chainOfTwoTransfers(), -1, ANCHOR, NOW, Budget.of(1000));

        assertThat(result.failed()).isTrue();
        // 첫 대기의 no-prediction 과 가른다. 체인을 켜서 는 제외를 지표로 읽을 수 있어야 한다.
        assertThat(result.failureReason()).isEqualTo("boarding-unresolved");
    }

    @Test
    @DisplayName("첫 대기를 못 풀면 no-prediction — 옛 이름 그대로다")
    void unresolvedFirstWaitKeepsTheOldReason() {
        supports();
        given(busEstimator.estimate(any(), any(), any(), anyLong()))
                .willReturn(BoardingOutcome.none());

        assertThat(calculator.compute(chainOfTwoTransfers(), -1, ANCHOR, NOW, Budget.of(1000))
                .failureReason()).isEqualTo("no-prediction");
    }

    @Test
    @DisplayName("못 타는 차가 하나라도 있으면 버린다")
    void unboardableDropsTheCandidate() {
        supports();
        given(busEstimator.estimate(any(), any(), any(), anyLong()))
                .willReturn(boarding(ANCHOR.plusSeconds(180)))
                .willReturn(BoardingOutcome.of(new BoardingEstimate(ANCHOR.plusSeconds(60), false,
                        CurrentLegArrival.Source.REALTIME_ARRIVAL, CurrentLegArrival.Quality.GOOD,
                        NOW, null)));

        assertThat(calculator.compute(chainOfTwoTransfers(), -1, ANCHOR, NOW, Budget.of(1000))
                .failureReason()).isEqualTo("unboardable");
    }

    @Test
    @DisplayName("신뢰도는 가장 나쁜 고리가 정한다")
    void qualityIsTheWorstLink() {
        supports();
        given(busEstimator.estimate(any(), any(), any(), anyLong()))
                .willReturn(boarding(ANCHOR.plusSeconds(180)))
                .willReturn(BoardingOutcome.of(new BoardingEstimate(
                        ANCHOR.plusSeconds(1200), true, CurrentLegArrival.Source.MODEL_ARRIVAL,
                        CurrentLegArrival.Quality.APPROXIMATE, NOW, "m1")));

        Result result = calculator.compute(chainOfTwoTransfers(), -1, ANCHOR, NOW, Budget.of(1000));

        assertThat(result.quality()).isEqualTo(CurrentLegArrival.Quality.APPROXIMATE);
        // 화면이 "이 차를 타세요"로 쓰는 값은 첫 고리다.
        assertThat(result.firstEstimate().boardingAt()).isEqualTo(ANCHOR.plusSeconds(180));
    }

    @Test
    @DisplayName("소요가 빈 구간이 있으면 버린다 — 0으로 치면 도착이 빨라진다")
    void missingDurationDropsTheCandidate() {
        supports();
        given(busEstimator.estimate(any(), any(), any(), anyLong()))
                .willReturn(boarding(ANCHOR.plusSeconds(180)));

        List<Step> steps = List.of(
                Step.of(0, JourneyLegType.WALK, null),
                new Step(1, JourneyLegType.WAIT_BUS, null, query()),
                Step.of(2, JourneyLegType.RIDE_BUS, 600));

        assertThat(calculator.compute(steps, -1, ANCHOR, NOW, Budget.of(1000)).failureReason())
                .isEqualTo("missing-duration");
    }

    @Test
    @DisplayName("예산이 없으면 물어보지 않는다")
    void zeroBudgetStopsTheChain() {
        supports();

        assertThat(calculator.compute(chainOfTwoTransfers(), -1, ANCHOR, NOW, Budget.of(0))
                .failureReason()).isEqualTo("budget-exhausted");
    }

    @Test
    @DisplayName("이미 지난 번호는 건너뛴다 — 저장된 여정의 앞 구간이 두 번 더해지지 않는다")
    void skipsLegsBeforeTheAnchor() {
        supports();
        given(busEstimator.estimate(any(), any(), any(), anyLong()))
                .willAnswer(call -> boarding(call.getArgument(1, Instant.class).plusSeconds(60)));

        // 0(도보)·1(첫 대기)은 이미 앵커에 반영돼 있다. 2(주행)부터 센다 — 저장된 여정의 기준선이
        // 딱 이 모양으로 부른다(앞 구간을 다시 더하면 기준선만 늦어져 가짜 개선폭이 된다).
        Result result = calculator.compute(chainOfTwoTransfers(), 1, ANCHOR, NOW, Budget.of(1000));

        assertThat(result.destinationEtaAt()).isEqualTo(ANCHOR.plusSeconds(600 + 120 + 60 + 300));
        assertThat(result.boardings()).isEqualTo(1);
    }

    @Test
    @DisplayName("탈 차가 없는 경로는 소요만 더한다")
    void walkOnlyJustSumsDurations() {
        Result result = calculator.compute(
                List.of(Step.of(0, JourneyLegType.WALK, 900)), -1, ANCHOR, NOW, Budget.of(1000));

        assertThat(result.failed()).isFalse();
        assertThat(result.destinationEtaAt()).isEqualTo(ANCHOR.plusSeconds(900));
        assertThat(result.firstEstimate()).isNull();
        assertThat(result.boardings()).isZero();
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    /** 도보 60 → 버스 600 → 환승 도보 120 → 버스 300. 대기가 둘이다. */
    private List<Step> chainOfTwoTransfers() {
        return List.of(
                Step.of(0, JourneyLegType.WALK, 60),
                new Step(1, JourneyLegType.WAIT_BUS, null, query()),
                Step.of(2, JourneyLegType.RIDE_BUS, 600),
                Step.of(3, JourneyLegType.TRANSFER_WALK, 120),
                new Step(4, JourneyLegType.WAIT_BUS, null, query()),
                Step.of(5, JourneyLegType.RIDE_BUS, 300));
    }

    private void supports() {
        given(busEstimator.supports(JourneyLegType.WAIT_BUS)).willReturn(true);
    }

    private BoardingQuery query() {
        return new BoardingQuery(JourneyLegType.WAIT_BUS, "ST-C", "정류장C", "BL-360", 4, null);
    }

    private BoardingOutcome boarding(Instant boardingAt) {
        return BoardingOutcome.of(new BoardingEstimate(boardingAt, true,
                CurrentLegArrival.Source.REALTIME_ARRIVAL, CurrentLegArrival.Quality.GOOD,
                NOW, null));
    }
}
