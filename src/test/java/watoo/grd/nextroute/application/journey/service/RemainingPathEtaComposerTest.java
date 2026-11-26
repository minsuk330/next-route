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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
            compiler, enricher,
            new BoardingChainEtaCalculator(List.of(busEstimator), Clock.fixed(NOW, ZoneOffset.UTC)));

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
        // 정류장까지 가는 도보 180초 + 하차 후 도보 240초. 체인은 <b>경로 전체</b>의 걷기를 센다 —
        // 그 값이 화면에도 나가고 줄 세우기에도 쓰이므로, 접근 도보를 빼면 멀리 걸어가야 하는
        // 후보가 실제보다 편해 보인다.
        assertThat(attempt.eta().walkingSec()).isEqualTo(420);
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

    // ── overlay 체인(PR-1) ──────────────────────────────────────────────────

    @Test
    @DisplayName("대기가 둘이면 둘 다 물어본다 — 두 번째 앵커는 첫 차를 타고 간 뒤다")
    void chainsEveryWait() {
        givenEnriched(twoTransferPlan());
        givenBoardingChain();

        Attempt attempt = composer.compose(composer.prepare(anyPath()), READY_AT, NOW, 1500);

        ArgumentCaptor<Instant> anchors = ArgumentCaptor.forClass(Instant.class);
        verify(busEstimator, times(2)).estimate(any(BoardingQuery.class), anchors.capture(),
                any(), anyLong());
        assertThat(anchors.getAllValues().get(0)).isEqualTo(READY_AT);
        // 첫 승차(+120) + 주행 600 + 환승 도보 120.
        assertThat(anchors.getAllValues().get(1)).isEqualTo(READY_AT.plusSeconds(840));
        // 목적지 = 두 번째 승차(+120) + 주행 600. 두 번의 대기가 <b>0으로 접히지 않았다</b>.
        assertThat(attempt.eta().destinationEtaAt()).isEqualTo(READY_AT.plusSeconds(1560));
        assertThat(attempt.eta().waitingSec()).isEqualTo(240);
    }

    @Test
    @DisplayName("두 번째 대기를 못 풀면 후보가 아니다 — 0으로 두면 없는 개선폭이 생긴다")
    void dropsCandidateWhenLaterWaitIsUnresolved() {
        givenEnriched(twoTransferPlan());
        given(busEstimator.supports(JourneyLegType.WAIT_BUS)).willReturn(true);
        given(busEstimator.estimate(any(), any(), any(), anyLong()))
                .willReturn(BoardingOutcome.of(new BoardingEstimate(READY_AT.plusSeconds(120), true,
                        CurrentLegArrival.Source.REALTIME_ARRIVAL, CurrentLegArrival.Quality.GOOD,
                        NOW, null)))
                .willReturn(BoardingOutcome.none());

        Attempt attempt = composer.compose(composer.prepare(anyPath()), READY_AT, NOW, 1500);

        assertThat(attempt.failed()).isTrue();
        assertThat(attempt.filterReason()).isEqualTo("boarding-unresolved");
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    /** 버스 대기 → 주행 600 → 환승 도보 120 → 버스 대기 → 주행 600. */
    private JourneyRouteOptionDraft regionalOption(String sourceRouteId) {
        return new JourneyRouteOptionDraft(0, 0, null, sourceRouteId, "720-3", null, null, null, null);
    }

    @Test
    @DisplayName("PR-1: 경기 노선 첫 대기(서울 노선·순번 없음)는 버리지 않고, 질의에 지역 노선 id 를 싣는다")
    void regionalFirstWaitIsComposedWithSourceRouteId() {
        givenEnriched(new JourneyPlan(List.of(
                walkDraft(0, 180, "센트럴파크"),
                waitDraft(1, JourneyLegType.WAIT_BUS, "233001674", regionalOption("234000068")),
                rideDraft(2, 900),
                walkDraft(3, 240, "목적지")), List.of()));
        givenBoarding(READY_AT.plusSeconds(600), true);

        Prepared prepared = composer.prepare(anyPath());
        Attempt attempt = composer.compose(prepared, READY_AT, NOW, 1500);

        assertThat(prepared.failed()).isFalse();
        assertThat(prepared.usesRegionalRealtime()).isTrue();
        assertThat(attempt.failed()).isFalse();
        ArgumentCaptor<BoardingQuery> query = ArgumentCaptor.forClass(BoardingQuery.class);
        verify(busEstimator).estimate(query.capture(), any(), any(), anyLong());
        assertThat(query.getValue().sourceRouteId()).isEqualTo("234000068");
        assertThat(query.getValue().stopId()).isEqualTo("233001674");
        assertThat(query.getValue().routeId()).isNull();
    }

    @Test
    @DisplayName("PR-1: 뒤 대기가 안 풀리는 후보는 물어보기 전에 버린다 — 앞 대기에서 몫을 쓰고 버리지 않게")
    void laterUnresolvedWaitIsFilteredBeforeAnyCall() {
        givenEnriched(new JourneyPlan(List.of(
                waitDraft(0, JourneyLegType.WAIT_BUS, "233001674", regionalOption("234000068")),
                rideDraft(1, 600),
                transferWalkDraft(2, 120),
                waitDraft(3, JourneyLegType.WAIT_BUS, "1:BS_ACC3_33020010", regionalOption(null)),
                rideDraft(4, 600)), List.of()));

        Prepared prepared = composer.prepare(anyPath());

        assertThat(prepared.failureReason()).isEqualTo("boarding-unresolved");
    }

    @Test
    @DisplayName("PR-1: 서울만 타는 후보의 뒤 대기 미해석은 예전처럼 prepare 를 통과한다(보드 자리 '평가 못 함' 유지)")
    void seoulCandidateWithUnresolvedLaterWaitStillPrepares() {
        givenEnriched(new JourneyPlan(List.of(
                waitDraft(0, JourneyLegType.WAIT_BUS, "ST-C", option("BL-360", 5, null)),
                rideDraft(1, 600),
                transferWalkDraft(2, 120),
                waitDraft(3, JourneyLegType.WAIT_BUS, "1:BS_ACC3_33020010", regionalOption(null)),
                rideDraft(4, 600)), List.of()));

        assertThat(composer.prepare(anyPath()).failed()).isFalse();
    }

    @Test
    @DisplayName("PR-1: 서울 → 경기 두 대기 체인이 끝까지 계산되고, 뒤 대기 질의에 지역 노선 id 가 실린다")
    void seoulThenRegionalChainComposes() {
        givenEnriched(new JourneyPlan(List.of(
                waitDraft(0, JourneyLegType.WAIT_BUS, "ST-C", option("BL-360", 5, null)),
                rideDraft(1, 600),
                transferWalkDraft(2, 120),
                waitDraft(3, JourneyLegType.WAIT_BUS, "233001562", regionalOption("234000995")),
                rideDraft(4, 600)), List.of()));
        givenBoardingChain();

        Prepared prepared = composer.prepare(anyPath());
        Attempt attempt = composer.compose(prepared, READY_AT, NOW, 1500);

        assertThat(prepared.regionalLookups()).isEqualTo(1);
        assertThat(attempt.failed()).isFalse();
        ArgumentCaptor<BoardingQuery> queries = ArgumentCaptor.forClass(BoardingQuery.class);
        verify(busEstimator, org.mockito.Mockito.times(2)).estimate(queries.capture(), any(), any(), anyLong());
        assertThat(queries.getAllValues()).extracting(BoardingQuery::sourceRouteId).containsExactly("SRC", "234000995");
    }

    @Test
    @DisplayName("PR-1: 서울 노선만 있는 후보는 지역 실시간을 쓰지 않는다(회차 상한에 세지 않음)")
    void seoulOnlyCandidateDoesNotUseRegional() {
        givenEnriched(busPlan("ST-C", "BL-360", 5));

        assertThat(composer.prepare(anyPath()).usesRegionalRealtime()).isFalse();
    }

    private JourneyPlan twoTransferPlan() {
        return new JourneyPlan(List.of(
                waitDraft(0, JourneyLegType.WAIT_BUS, "ST-C", option("BL-360", 5, null)),
                rideDraft(1, 600),
                transferWalkDraft(2, 120),
                waitDraft(3, JourneyLegType.WAIT_BUS, "ST-E", option("BL-100", 3, null)),
                rideDraft(4, 600)), List.of());
    }

    /** 물어본 앵커에서 120초 뒤에 탄다고 답한다. 체인이 앵커를 미는지 그대로 드러난다. */
    private void givenBoardingChain() {
        given(busEstimator.supports(JourneyLegType.WAIT_BUS)).willReturn(true);
        given(busEstimator.estimate(any(), any(), any(), anyLong()))
                .willAnswer(call -> BoardingOutcome.of(new BoardingEstimate(
                        call.getArgument(1, Instant.class).plusSeconds(120), true,
                        CurrentLegArrival.Source.REALTIME_ARRIVAL, CurrentLegArrival.Quality.GOOD,
                        NOW, null)));
    }

    private JourneyLegDraft transferWalkDraft(int legIndex, int durationSec) {
        return new JourneyLegDraft(legIndex, JourneyLegType.TRANSFER_WALK, JourneyLegState.PENDING,
                null, null, null, null, "정류장D", "정류장E", 127.4, 37.4, 127.45, 37.45,
                durationSec, null, List.of(), null);
    }

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
