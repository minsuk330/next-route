package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import watoo.grd.nextroute.application.journey.dto.BoardingEstimate;
import watoo.grd.nextroute.application.journey.dto.BoardingOutcome;
import watoo.grd.nextroute.application.journey.dto.BoardingQuery;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.journey.dto.JourneyRouteOptionDraft;
import watoo.grd.nextroute.application.journey.port.out.BoardingTimeEstimator;
import watoo.grd.nextroute.application.journey.service.RemainingPathEtaComposer.Attempt;
import watoo.grd.nextroute.application.journey.service.RemainingPathEtaComposer.Prepared;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 검색이 준 경로 하나를 후보로 옮기는 규칙.
 *
 * <p>여기서 못박는 것 중 둘이 특히 조용히 틀린다.
 * <ul>
 *   <li><b>보강을 거친다</b> — 안 거치면 후보가 전부 "예측 없음"이 되는데 증상은 예측 API 문제로 보인다</li>
 *   <li><b>첫 대기 앞의 도보만큼 앵커가 밀린다</b> — 안 밀면 실제로는 못 타는 차를 후보로 만든다</li>
 * </ul>
 */
@DisplayName("남은 구간 후보 ETA 합성")
class RemainingPathEtaComposerTest {

    private static final Instant NOW = Instant.parse("2026-08-13T09:00:00Z");
    private static final Instant READY_AT = NOW.plusSeconds(300);

    private final JourneyCompiler compiler = new JourneyCompiler(new StopIdentityResolver());
    private final JourneyTransitIdEnricher enricher = mock(JourneyTransitIdEnricher.class);
    private final BoardingTimeEstimator busEstimator = mock(BoardingTimeEstimator.class);
    private final RemainingPathEtaComposer composer = new RemainingPathEtaComposer(
            compiler, enricher, new RouteOptionEtaCalculator(), List.of(busEstimator));

    @Test
    @DisplayName("보강을 거친 초안으로 물어본다 — 안 거치면 후보가 전부 예측 없음이 된다")
    void usesEnrichedDrafts() {
        givenEnriched(busPlan("ST-C", "BL-360", 5));

        Prepared prepared = composer.prepare(anyPath());

        verify(enricher).enrich(any(JourneyPlan.class));
        assertThat(prepared.failed()).isFalse();
        assertThat(prepared.firstRouteId()).isEqualTo("BL-360");
        assertThat(prepared.firstWait().startStopId()).isEqualTo("ST-C");
    }

    @Test
    @DisplayName("첫 대기 앞의 도보만큼 앵커가 밀린다")
    void anchorIncludesWalkBeforeFirstWait() {
        givenEnriched(busPlan("ST-C", "BL-360", 5));
        givenBoarding(READY_AT.plusSeconds(600), true);

        composer.compose(composer.prepare(anyPath()), READY_AT, NOW, 1500);

        ArgumentCaptor<Instant> anchor = ArgumentCaptor.forClass(Instant.class);
        verify(busEstimator).estimate(any(BoardingQuery.class), anchor.capture(), any(), anyLong());
        // 첫 대기 앞 도보가 180초다. 그만큼 늦게 정류장에 선다.
        assertThat(anchor.getValue()).isEqualTo(READY_AT.plusSeconds(180));
    }

    @Test
    @DisplayName("목적지 ETA = 승차 시각 + 그 뒤 구간 소요 합")
    void destinationEtaSumsAfterBoarding() {
        givenEnriched(busPlan("ST-C", "BL-360", 5));
        Instant boardingAt = READY_AT.plusSeconds(600);
        givenBoarding(boardingAt, true);

        Attempt attempt = composer.compose(composer.prepare(anyPath()), READY_AT, NOW, 1500);

        assertThat(attempt.failed()).isFalse();
        // 주행 900초 + 하차 후 도보 240초.
        assertThat(attempt.eta().destinationEtaAt()).isEqualTo(boardingAt.plusSeconds(1140));
        assertThat(attempt.eta().walkingSec()).isEqualTo(240);
        assertThat(attempt.observedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("못 탈 차량은 후보가 아니다")
    void unboardableIsFiltered() {
        givenEnriched(busPlan("ST-C", "BL-360", 5));
        givenBoarding(READY_AT.minusSeconds(60), false);

        Attempt attempt = composer.compose(composer.prepare(anyPath()), READY_AT, NOW, 1500);

        assertThat(attempt.failed()).isTrue();
        assertThat(attempt.filterReason()).isEqualTo("unboardable");
    }

    @Test
    @DisplayName("예측이 없으면 no-prediction")
    void noPredictionIsFiltered() {
        givenEnriched(busPlan("ST-C", "BL-360", 5));
        given(busEstimator.supports(JourneyLegType.WAIT_BUS)).willReturn(true);
        given(busEstimator.estimate(any(), any(), any(), anyLong()))
                .willReturn(BoardingOutcome.none());

        Attempt attempt = composer.compose(composer.prepare(anyPath()), READY_AT, NOW, 1500);

        assertThat(attempt.filterReason()).isEqualTo("no-prediction");
    }

    @Test
    @DisplayName("승차 순번을 못 채웠으면 물어보기 전에 버린다")
    void unresolvedBoardingSeqIsFiltered() {
        givenEnriched(busPlan("ST-C", "BL-360", null));

        Prepared prepared = composer.prepare(anyPath());

        assertThat(prepared.failureReason()).isEqualTo("unresolved-route");
        verifyNoInteractions(busEstimator);
    }

    @Test
    @DisplayName("미해석 정류장이면 버린다")
    void unresolvedStopIsFiltered() {
        givenEnriched(busPlan(null, "BL-360", 5));

        assertThat(composer.prepare(anyPath()).failureReason()).isEqualTo("unresolved-route");
    }

    @Test
    @DisplayName("지하철인데 방향을 모르면 no-direction")
    void subwayWithoutWayCodeIsFiltered() {
        givenEnriched(subwayPlanWithoutWayCode());

        assertThat(composer.prepare(anyPath()).failureReason()).isEqualTo("no-direction");
    }

    @Test
    @DisplayName("걷기만으로 닿는 경로도 후보다 — 예측을 건너뛰고 준비 즉시 출발한다")
    void walkOnlyPathIsCandidate() {
        givenEnriched(walkOnlyPlan());

        Prepared prepared = composer.prepare(anyPath());
        Attempt attempt = composer.compose(prepared, READY_AT, NOW, 1500);

        assertThat(prepared.walkOnly()).isTrue();
        assertThat(attempt.failed()).isFalse();
        assertThat(attempt.estimate()).isNull();
        assertThat(attempt.eta().destinationEtaAt()).isEqualTo(READY_AT.plusSeconds(900));
        assertThat(attempt.observedAt()).isEqualTo(NOW);
        verifyNoInteractions(busEstimator);
    }

    @Test
    @DisplayName("첫 대기 앞 도보의 소요가 비면 버린다 — 0으로 치면 앵커가 앞당겨진다")
    void missingWalkDurationIsFiltered() {
        givenEnriched(new JourneyPlan(List.of(
                walkDraft(0, null, "정류장C"),
                waitDraft(1, JourneyLegType.WAIT_BUS, "ST-C", option("BL-360", 5, null)),
                rideDraft(2, 900)), List.of()));

        Attempt attempt = composer.compose(composer.prepare(anyPath()), READY_AT, NOW, 1500);

        assertThat(attempt.filterReason()).isEqualTo("missing-duration");
    }

    @Test
    @DisplayName("이후 대기 수를 센다 — 환승 수 관문이 쓰는 값이다")
    void countsWaitsAfterFirst() {
        givenEnriched(new JourneyPlan(List.of(
                waitDraft(0, JourneyLegType.WAIT_BUS, "ST-C", option("BL-360", 5, null)),
                rideDraft(1, 600),
                waitDraft(2, JourneyLegType.WAIT_BUS, "ST-E", option("BL-100", 3, null)),
                rideDraft(3, 600)), List.of()));

        Prepared prepared = composer.prepare(anyPath());

        assertThat(prepared.transferCount()).isEqualTo(2);
        assertThat(prepared.waitsAfterFirst()).isEqualTo(1);
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private void givenEnriched(JourneyPlan plan) {
        given(enricher.enrich(any(JourneyPlan.class))).willReturn(plan);
    }

    private void givenBoarding(Instant boardingAt, boolean boardable) {
        given(busEstimator.supports(JourneyLegType.WAIT_BUS)).willReturn(true);
        given(busEstimator.estimate(any(), any(), any(), anyLong()))
                .willReturn(BoardingOutcome.of(new BoardingEstimate(boardingAt, boardable,
                        CurrentLegArrival.Source.REALTIME_ARRIVAL, CurrentLegArrival.Quality.GOOD,
                        NOW, null)));
    }

    /** 도보 180초 → 버스 대기 → 주행 900초 → 도보 240초. */
    private JourneyPlan busPlan(String stopId, String routeId, Integer boardingSeq) {
        return new JourneyPlan(List.of(
                walkDraft(0, 180, "정류장C"),
                waitDraft(1, JourneyLegType.WAIT_BUS, stopId, option(routeId, boardingSeq, null)),
                rideDraft(2, 900),
                walkDraft(3, 240, "목적지")), List.of());
    }

    private JourneyPlan subwayPlanWithoutWayCode() {
        return new JourneyPlan(List.of(
                waitDraft(0, JourneyLegType.WAIT_SUBWAY, "ST-SUB", option("1002", null, null)),
                rideDraft(1, 600)), List.of());
    }

    private JourneyPlan walkOnlyPlan() {
        return new JourneyPlan(List.of(walkDraft(0, 900, "목적지")), List.of());
    }

    private JourneyRouteOptionDraft option(String routeId, Integer boardingSeq, Integer wayCode) {
        return new JourneyRouteOptionDraft(0, 0, routeId, "SRC", "360", null,
                wayCode, boardingSeq, null);
    }

    private JourneyLegDraft walkDraft(int legIndex, Integer durationSec, String endName) {
        return new JourneyLegDraft(legIndex, JourneyLegType.WALK, JourneyLegState.PENDING,
                null, null, null, null, "출발", endName, 127.0, 37.0, 127.1, 37.1,
                durationSec, null, List.of(), null);
    }

    private JourneyLegDraft waitDraft(int legIndex, JourneyLegType type, String stopId,
                                      JourneyRouteOptionDraft option) {
        return new JourneyLegDraft(legIndex, type, JourneyLegState.PENDING,
                stopId, stopId, null, null, "정류장C", "정류장C", 127.1, 37.1, 127.1, 37.1,
                null, null, List.of(option), legIndex + 1);
    }

    private JourneyLegDraft rideDraft(int legIndex, int durationSec) {
        return new JourneyLegDraft(legIndex, JourneyLegType.RIDE_BUS, JourneyLegState.PENDING,
                "ST-C", "ST-D", null, null, "정류장C", "정류장D", 127.1, 37.1, 127.4, 37.4,
                durationSec, null, List.of(), legIndex - 1);
    }

    /** 보강 결과를 통째로 갈아끼우므로 입력 경로의 내용은 중요하지 않다. */
    private PathResult anyPath() {
        return new PathResult(1, null,
                List.of(SubPathFixture.bus(15).names("정류장C", "정류장D")
                        .lanes(SubPathFixture.busLane("360", "BL-360")).build()),
                List.of());
    }
}
