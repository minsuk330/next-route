package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.BoardingEstimate;
import watoo.grd.nextroute.application.journey.dto.BoardingOutcome;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyRouteOptionDraft;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteContext;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.port.out.BoardingTimeEstimator;
import watoo.grd.nextroute.application.journey.service.JourneyWaypointResolver.Coord;
import watoo.grd.nextroute.application.journey.service.JourneyWaypointResolver.Waypoints;
import watoo.grd.nextroute.application.journey.service.RemainingPathEtaComposer.Attempt;
import watoo.grd.nextroute.application.journey.service.RemainingPathEtaComposer.Prepared;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 남은 구간 재검색 후보 생성.
 *
 * <p>못박는 것은 넷이다 — <b>유료 호출을 언제 하지 않는가</b>(미해석 정류장·같은 노선·검색 빈손),
 * 기준선이 있을 때와 없을 때의 두 갈래, 환승이 늘어나는 후보를 버리는 것, 그리고 후보 하나가
 * 터져도 나머지가 살아남는 것.
 */
@DisplayName("남은 구간 재검색 후보")
class RemainingPathCandidateProviderTest {

    private static final long JOURNEY_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-08-13T09:00:00Z");
    private static final Instant READY_AT = NOW.plusSeconds(300);
    private static final Waypoints WAYPOINTS =
            new Waypoints(new Coord(127.3, 37.3), new Coord(127.5, 37.5));

    private final TransferConnectionRepository transferRepository =
            mock(TransferConnectionRepository.class);
    private final JourneyLegRepository legRepository = mock(JourneyLegRepository.class);
    private final JourneyLegRouteOptionRepository routeOptionRepository =
            mock(JourneyLegRouteOptionRepository.class);
    private final JourneyWaypointResolver waypointResolver = mock(JourneyWaypointResolver.class);
    private final RemainingRouteSearchService searchService = mock(RemainingRouteSearchService.class);
    private final RemainingPathEtaComposer composer = mock(RemainingPathEtaComposer.class);
    private final BoardingTimeEstimator busEstimator = mock(BoardingTimeEstimator.class);
    private final JourneyRerouteProperties properties = new JourneyRerouteProperties();
    private final JourneyRerouteMetrics metrics = new JourneyRerouteMetrics(new SimpleMeterRegistry());

    /** overlay 병렬 풀. 테스트에서는 부르는 스레드에서 그대로 돈다 — 순서·시각이 흔들리지 않는다. */
    private final ExecutorService overlayExecutor = new AbstractExecutorService() {
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public void execute(Runnable command) { command.run(); }
    };

    private RemainingPathCandidateProvider provider;
    private JourneyLeg waitLeg;

    @BeforeEach
    void setUp() {
        provider = new RemainingPathCandidateProvider(
                transferRepository, legRepository, routeOptionRepository, waypointResolver,
                searchService, composer,
                new BoardingChainEtaCalculator(List.of(busEstimator), Clock.fixed(NOW, ZoneOffset.UTC)),
                new TransferPredictionInterpreter(new JourneyEvaluatorProperties()),
                properties, metrics, Clock.fixed(NOW, ZoneOffset.UTC), overlayExecutor);

        waitLeg = leg(2, JourneyLegType.WAIT_BUS, "ST-C", null);
        given(transferRepository.findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(
                JOURNEY_ID, 1, 0)).willReturn(Optional.of(transfer()));
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                JOURNEY_ID, 1)).willReturn(legs());
        given(waypointResolver.resolve(JOURNEY_ID, 1, 2)).willReturn(Optional.of(WAYPOINTS));
    }

    @Test
    @DisplayName("이 공급자는 ③ 다음이다")
    void kindAndPriority() {
        assertThat(provider.kind()).isEqualTo(RerouteProposalKind.REMAINING_PATH);
        assertThat(provider.priority()).isEqualTo(1);
    }

    @Test
    @DisplayName("개선폭이 임계값을 넘으면 후보가 된다 — 적용 재료는 경로 원본 그대로다")
    void fasterPathBecomesCandidate() {
        PathResult path = path("A");
        givenSearch(path);
        givenPrepared(path, prepared("BL-360", 0, 1));
        givenBaseline(NOW.plusSeconds(600), true);
        givenComposed(path, attempt(NOW.plusSeconds(2400)));

        List<RerouteCandidate> candidates = provider.findCandidates(context());

        assertThat(candidates).hasSize(1);
        RerouteCandidate candidate = candidates.get(0);
        assertThat(candidate.kind()).isEqualTo(RerouteProposalKind.REMAINING_PATH);
        assertThat(candidate.display().etaGainSec()).isEqualTo(600);
        assertThat(candidate.display().confidence()).isEqualTo("GOOD");
        assertThat(candidate.display().transferCount()).isEqualTo(1);
        assertThat(candidate.apply()).isInstanceOf(RerouteCandidateApply.RemainingPathApply.class);
        RerouteCandidateApply.RemainingPathApply apply =
                (RerouteCandidateApply.RemainingPathApply) candidate.apply();
        assertThat(apply.fromLegIndex()).isEqualTo(2);
        assertThat(apply.path()).isSameAs(path);
    }

    @Test
    @DisplayName("개선폭이 임계값 미만이면 빈 결과다")
    void smallGainIsFiltered() {
        PathResult path = path("A");
        givenSearch(path);
        givenPrepared(path, prepared("BL-360", 0, 1));
        givenBaseline(NOW.plusSeconds(600), true);
        givenComposed(path, attempt(NOW.plusSeconds(2900)));

        assertThat(provider.findCandidates(context())).isEmpty();
    }

    @Test
    @DisplayName("기준선을 못 재면 가장 이른 하나만, 개선폭 0 · 신뢰도 낮춤")
    void withoutBaselineOnlyEarliestSurvives() {
        PathResult first = path("A");
        PathResult second = path("B");
        givenSearch(first, second);
        givenPrepared(first, prepared("BL-360", 0, 1));
        givenPrepared(second, prepared("BL-100", 0, 1));
        // 지금 차를 아예 못 탄다 = 기준선 없음. ③이라면 빈손이지만 ④는 계속 간다.
        givenBaseline(NOW.plusSeconds(600), false);
        givenComposed(first, attempt(NOW.plusSeconds(3000)));
        givenComposed(second, attempt(NOW.plusSeconds(2400)));

        List<RerouteCandidate> candidates = provider.findCandidates(context());

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).display().destinationEtaAt()).isEqualTo(NOW.plusSeconds(2400));
        assertThat(candidates.get(0).display().etaGainSec()).isZero();
        assertThat(candidates.get(0).display().confidence()).isEqualTo("APPROXIMATE");
    }

    @Test
    @DisplayName("지금 타려던 노선으로 시작하는 경로는 예측 전에 버린다")
    void sameAsCurrentIsFilteredBeforePrediction() {
        waitLeg.selectRouteOption(11L, "BL-721");
        PathResult path = path("A");
        givenSearch(path);
        givenPrepared(path, prepared("BL-721", 0, 1));

        assertThat(provider.findCandidates(context())).isEmpty();
        verify(composer, never()).compose(any(), any(), any(),
                any(BoardingChainEtaCalculator.Budget.class));
        verifyNoInteractions(busEstimator);
    }

    @Test
    @DisplayName("환승이 기준선보다 많은 후보는 버린다 — 없는 개선폭이 만들어진다")
    void moreTransfersIsFiltered() {
        PathResult path = path("A");
        givenSearch(path);
        // 기준선은 첫 대기 이후 대기가 0개인데 후보는 1개다.
        givenPrepared(path, prepared("BL-360", 1, 2));
        givenBaseline(NOW.plusSeconds(600), true);

        assertThat(provider.findCandidates(context())).isEmpty();
        verify(composer, never()).compose(any(), any(), any(),
                any(BoardingChainEtaCalculator.Budget.class));
    }

    @Test
    @DisplayName("미해석 정류장이면 검색을 부르지 않는다 — 캐시 키가 섞인다")
    void unresolvedStopSkipsSearch() {
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                JOURNEY_ID, 1)).willReturn(List.of(leg(2, JourneyLegType.WAIT_BUS, null, null)));

        assertThat(provider.findCandidates(context())).isEmpty();
        verifyNoInteractions(searchService);
    }

    @Test
    @DisplayName("좌표를 못 찾으면 검색을 부르지 않는다")
    void missingWaypointsSkipsSearch() {
        given(waypointResolver.resolve(JOURNEY_ID, 1, 2)).willReturn(Optional.empty());

        assertThat(provider.findCandidates(context())).isEmpty();
        verifyNoInteractions(searchService);
    }

    @Test
    @DisplayName("검색이 빈손이면 기준선을 재지 않는다 — 잴 이유가 없다")
    void emptySearchSkipsBaseline() {
        given(searchService.search(eq("ST-C"), eq(WAYPOINTS), anyLong())).willReturn(List.of());

        assertThat(provider.findCandidates(context())).isEmpty();
        verifyNoInteractions(busEstimator);
    }

    @Test
    @DisplayName("남는 경로가 없으면 기준선을 재지 않는다")
    void allPathsFilteredSkipsBaseline() {
        PathResult path = path("A");
        givenSearch(path);
        givenPrepared(path, Prepared.failed("unresolved-route"));

        assertThat(provider.findCandidates(context())).isEmpty();
        verifyNoInteractions(busEstimator);
    }

    @Test
    @DisplayName("경로는 상한(max-paths)까지만 평가한다")
    void trimsToMaxPaths() {
        // 트림 메커니즘 자체를 검증 — 기본값(PR4a=4)과 독립되게 상한을 2 로 고정한다.
        properties.getRemainingPath().setMaxPaths(2);
        PathResult first = path("A");
        PathResult second = path("B");
        PathResult third = path("C");
        givenSearch(first, second, third);
        givenPrepared(first, prepared("BL-360", 0, 1));
        givenPrepared(second, prepared("BL-100", 0, 1));
        givenPrepared(third, prepared("BL-200", 0, 1));
        givenBaseline(NOW.plusSeconds(600), true);
        givenComposed(first, attempt(NOW.plusSeconds(2400)));
        givenComposed(second, attempt(NOW.plusSeconds(2300)));

        List<RerouteCandidate> candidates = provider.findCandidates(context());

        assertThat(candidates).hasSize(2);
        // 상한을 넘은 경로는 쪼개 보지도 않는다.
        verify(composer, never()).prepare(third);
        // 개선폭이 큰 쪽이 먼저다.
        assertThat(candidates.get(0).display().etaGainSec())
                .isGreaterThan(candidates.get(1).display().etaGainSec());
    }

    @Test
    @DisplayName("후보 하나가 터져도 이미 모은 후보는 살아남는다")
    void oneFailureKeepsOthers() {
        PathResult first = path("A");
        PathResult second = path("B");
        givenSearch(first, second);
        Prepared firstPrepared = prepared("BL-360", 0, 1);
        Prepared secondPrepared = prepared("BL-100", 0, 1);
        givenPrepared(first, firstPrepared);
        givenPrepared(second, secondPrepared);
        givenBaseline(NOW.plusSeconds(600), true);
        given(composer.compose(eq(firstPrepared), any(), any(),
                any(BoardingChainEtaCalculator.Budget.class)))
                .willReturn(attempt(NOW.plusSeconds(2400)));
        willThrow(new IllegalStateException("provider down")).given(composer)
                .compose(eq(secondPrepared), any(), any(),
                        any(BoardingChainEtaCalculator.Budget.class));

        assertThat(provider.findCandidates(context())).hasSize(1);
    }

    @Test
    @DisplayName("기준선도 남은 환승을 물어본다 — 한쪽만 대기 0으로 재면 그 차이가 개선폭이 된다")
    void baselineOverlaysEveryRemainingWait() {
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                JOURNEY_ID, 1)).willReturn(legsWithSecondTransfer());
        PathResult path = path("A");
        givenSearch(path);
        givenPrepared(path, prepared("BL-360", 1, 2));
        // 물어본 앵커에서 300초 뒤에 탄다고 답한다.
        given(routeOptionRepository.findById(11L)).willReturn(Optional.of(busOption()));
        given(busEstimator.supports(JourneyLegType.WAIT_BUS)).willReturn(true);
        given(busEstimator.estimate(any(), any(), any(), anyLong()))
                .willAnswer(call -> BoardingOutcome.of(new BoardingEstimate(
                        call.getArgument(1, Instant.class).plusSeconds(300), true,
                        CurrentLegArrival.Source.REALTIME_ARRIVAL, CurrentLegArrival.Quality.GOOD,
                        NOW, null)));
        givenComposed(path, attempt(NOW.plusSeconds(2400)));

        List<RerouteCandidate> candidates = provider.findCandidates(context());

        ArgumentCaptor<Instant> anchors = ArgumentCaptor.forClass(Instant.class);
        verify(busEstimator, times(2)).estimate(any(), anchors.capture(), any(), anyLong());
        assertThat(anchors.getAllValues().get(0)).isEqualTo(READY_AT);
        // 두 번째 대기의 앵커 = 첫 승차(+300) + 주행 2400 + 환승 도보 120.
        assertThat(anchors.getAllValues().get(1)).isEqualTo(READY_AT.plusSeconds(2820));
        // 기준선 ETA = 두 번째 승차(+300) + 주행 600 = READY_AT+3720. 두 번의 대기가 살아 있다.
        // 개선폭 = 3720 + 300(READY_AT 오프셋) - 2400 = 1620.
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).display().etaGainSec()).isEqualTo(1620);
    }

    @Test
    @DisplayName("같은 도착 시각이면 덜 걷는 후보가 앞이다 — 표준 줄 세우기를 쓴다")
    void ordersByTheStandardRanking() {
        PathResult walkMore = path("A");
        PathResult walkLess = path("B");
        givenSearch(walkMore, walkLess);
        givenPrepared(walkMore, prepared("BL-360", 0, 1));
        givenPrepared(walkLess, prepared("BL-100", 0, 1));
        givenBaseline(NOW.plusSeconds(600), true);
        givenComposed(walkMore, attempt(NOW.plusSeconds(2400), 900));
        givenComposed(walkLess, attempt(NOW.plusSeconds(2400), 120));

        List<RerouteCandidate> candidates = provider.findCandidates(context());

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).display().walkingSec()).isEqualTo(120);
        assertThat(candidates.get(1).display().walkingSec()).isEqualTo(900);
    }

    @Test
    @DisplayName("환승 정보가 없으면 아무것도 하지 않는다")
    void missingTransferIsEmpty() {
        given(transferRepository.findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(
                JOURNEY_ID, 1, 0)).willReturn(Optional.empty());

        assertThat(provider.findCandidates(context())).isEmpty();
        verifyNoInteractions(searchService);
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private RerouteContext context() {
        return new RerouteContext(JOURNEY_ID, 3L, 1, 5L, 0, NOW);
    }

    private void givenSearch(PathResult... paths) {
        given(searchService.search(eq("ST-C"), eq(WAYPOINTS), anyLong())).willReturn(List.of(paths));
    }

    private void givenPrepared(PathResult path, Prepared prepared) {
        given(composer.prepare(path)).willReturn(prepared);
    }

    private void givenComposed(PathResult path, Attempt attempt) {
        given(composer.compose(eq(composer.prepare(path)), any(), any(),
                any(BoardingChainEtaCalculator.Budget.class))).willReturn(attempt);
    }

    private void givenBaseline(Instant boardingAt, boolean boardable) {
        given(routeOptionRepository.findById(11L)).willReturn(Optional.of(busOption()));
        given(busEstimator.supports(JourneyLegType.WAIT_BUS)).willReturn(true);
        given(busEstimator.estimate(any(), any(), any(), anyLong()))
                .willReturn(BoardingOutcome.of(new BoardingEstimate(boardingAt, boardable,
                        CurrentLegArrival.Source.REALTIME_ARRIVAL, CurrentLegArrival.Quality.GOOD,
                        NOW, null)));
    }

    /** 기준선 ETA = 승차 시각 + 주행 2400초. 후보와 견줄 값이다. */
    private List<JourneyLeg> legs() {
        return List.of(
                leg(0, JourneyLegType.WALK, null, 180),
                leg(1, JourneyLegType.RIDE_BUS, null, 600),
                waitLeg,
                leg(3, JourneyLegType.RIDE_BUS, null, 2400));
    }

    /** 기준선에 환승이 하나 더 남아 있는 여정. 체인이 그 대기까지 물어보는지 보는 데 쓴다. */
    private List<JourneyLeg> legsWithSecondTransfer() {
        return List.of(
                leg(0, JourneyLegType.WALK, null, 180),
                leg(1, JourneyLegType.RIDE_BUS, null, 600),
                waitLeg,
                leg(3, JourneyLegType.RIDE_BUS, null, 2400),
                leg(4, JourneyLegType.TRANSFER_WALK, null, 120),
                leg(5, JourneyLegType.WAIT_BUS, "ST-E", null),
                leg(6, JourneyLegType.RIDE_BUS, null, 600));
    }

    private Prepared prepared(String routeId, int waitsAfterFirst, int transferCount) {
        JourneyRouteOptionDraft option = new JourneyRouteOptionDraft(0, 0, routeId, "SRC",
                "360", null, null, 5, null);
        List<JourneyLegDraft> legs = new java.util.ArrayList<>();
        legs.add(waitDraft(0, option));
        legs.add(rideDraft(1));
        for (int i = 0; i < waitsAfterFirst; i++) {
            legs.add(waitDraft(legs.size(), option));
            legs.add(rideDraft(legs.size()));
        }
        Prepared prepared = new Prepared(legs, legs.get(0), option, null);
        assertThat(prepared.transferCount()).isEqualTo(transferCount);
        return prepared;
    }

    private Attempt attempt(Instant destinationEtaAt) {
        return attempt(destinationEtaAt, 240);
    }

    private Attempt attempt(Instant destinationEtaAt, int walkingSec) {
        return new Attempt(prepared("BL-360", 0, 1),
                new BoardingChainEtaCalculator.Result(destinationEtaAt, 1200, walkingSec, 300,
                        new BoardingEstimate(NOW.plusSeconds(900), true,
                                CurrentLegArrival.Source.REALTIME_ARRIVAL,
                                CurrentLegArrival.Quality.GOOD, NOW, null),
                        CurrentLegArrival.Quality.GOOD, 1, null),
                new BoardingEstimate(NOW.plusSeconds(900), true,
                        CurrentLegArrival.Source.REALTIME_ARRIVAL, CurrentLegArrival.Quality.GOOD,
                        NOW, null),
                NOW, null);
    }

    private JourneyLegDraft waitDraft(int legIndex, JourneyRouteOptionDraft option) {
        return new JourneyLegDraft(legIndex, JourneyLegType.WAIT_BUS, JourneyLegState.PENDING,
                "ST-C", "ST-C", null, null, "정류장C", "정류장C", 127.3, 37.3, 127.3, 37.3,
                null, null, List.of(option), legIndex + 1);
    }

    private JourneyLegDraft rideDraft(int legIndex) {
        return new JourneyLegDraft(legIndex, JourneyLegType.RIDE_BUS, JourneyLegState.PENDING,
                "ST-C", "ST-D", null, null, "정류장C", "정류장D", 127.3, 37.3, 127.5, 37.5,
                1200, null, List.of(), legIndex - 1);
    }

    private TransferConnection transfer() {
        TransferConnection transfer = TransferConnection.builder()
                .journeyId(JOURNEY_ID).routeRevision(1).transferIndex(0)
                .fromLegIndex(1).toLegIndex(2)
                .status(TransferStatus.UNKNOWN)
                .build();
        ReflectionTestUtils.setField(transfer, "userReadyAt", READY_AT);
        return transfer;
    }

    private JourneyLeg leg(int legIndex, JourneyLegType type, String stopId, Integer durationSec) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(JOURNEY_ID)
                .routeRevision(1)
                .legIndex(legIndex)
                .type(type)
                .state(JourneyLegState.PENDING)
                .startStopId(stopId)
                .startName("정류장C")
                .plannedDurationSec(durationSec)
                .build();
        if (type.isWaiting()) {
            leg.selectRouteOption(11L, "BL-999");
        }
        return leg;
    }

    private JourneyLegRouteOption busOption() {
        return JourneyLegRouteOption.builder()
                .journeyLegId(21L).optionIndex(0).laneIndex(0)
                .routeId("BL-999").routeName("999").boardingSeq(4)
                .build();
    }

    private PathResult path(String tag) {
        return new PathResult(1, null,
                List.of(SubPathFixture.bus(15).names("정류장C", tag)
                        .lanes(SubPathFixture.busLane("360", "BL-360")).build()),
                List.of());
    }
}
