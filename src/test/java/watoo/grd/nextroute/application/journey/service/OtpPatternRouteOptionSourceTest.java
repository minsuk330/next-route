package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver;
import watoo.grd.nextroute.application.route.mapping.SeoulBusIdentity;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.StopPattern;
import watoo.grd.nextroute.application.route.routing.StopPatternPort;
import watoo.grd.nextroute.application.route.routing.StopPatternResult;
import watoo.grd.nextroute.application.route.service.TransferStopResolver;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OTP pattern 에서 대안 후보를 빚는 계약.
 *
 * <p>무게중심은 <b>탈 수 없는·엉뚱한 후보를 만들지 않는지</b>다. 방향이 반대이거나, 서울 노선에
 * 매핑되지 않거나, 승차 순번을 단건으로 확정하지 못하면 후보에서 뺀다("모름&gt;틀림").
 */
@DisplayName("OTP pattern 대안 후보 출처")
class OtpPatternRouteOptionSourceTest {

    private static final String BOARD_GTFS = "1:BS_TAGO_SEB101000041";
    private static final String ALIGHT_GTFS = "1:BS_TAGO_SEB101000059";
    private static final String ROUTE_GTFS = "1:BR_TAGO_SEB100100022";

    private final StopPatternPort stopPatternPort = mock(StopPatternPort.class);
    private final OtpSeoulIdentityResolver identityResolver = mock(OtpSeoulIdentityResolver.class);
    private final TransferStopResolver busResolver = mock(TransferStopResolver.class);

    private OtpPatternRouteOptionSource source;
    private JourneyLeg waitLeg;

    @BeforeEach
    void setUp() {
        source = new OtpPatternRouteOptionSource(stopPatternPort, identityResolver, busResolver);
        waitLeg = waitLeg(BOARD_GTFS, ALIGHT_GTFS);
    }

    private RerouteRouteOptionSource.SourceOutcome run() {
        return source.optionsFor(waitLeg, RoutingDeadline.of(Clock.systemUTC(), 3000));
    }

    @Test
    @DisplayName("정차 순서가 승차→하차 방향이고 매핑·순번이 되면 동적 후보를 만든다")
    void makesDynamicCandidate() {
        givenPatterns(pattern(ROUTE_GTFS, "143", BOARD_GTFS, "1:BS_MID", ALIGHT_GTFS));
        givenResolvedBus(ROUTE_GTFS, "STOP1", "R1", "143");
        givenSeq("R1", "STOP1", 5);

        RerouteRouteOptionSource.SourceOutcome outcome = run();

        assertThat(outcome.isOk()).isTrue();
        assertThat(outcome.options()).hasSize(1);
        JourneyLegRouteOption option = outcome.options().get(0);
        // 저장 목록에 없는 동적 후보임을 optionIndex 음수로 표시한다(소비자가 스냅샷 수락으로 가른다).
        assertThat(option.getOptionIndex()).isNegative();
        assertThat(option.getRouteId()).isEqualTo("R1");
        assertThat(option.getSourceRouteId()).isEqualTo(ROUTE_GTFS);
        assertThat(option.getRouteName()).isEqualTo("143");
        assertThat(option.getBoardingSeq()).isEqualTo(5);
        assertThat(option.getId()).isNull(); // 임시 엔티티 — 저장 전이다.
    }

    @Test
    @DisplayName("정차 순서가 하차→승차(반대 방향)면 제외한다")
    void excludesWrongDirection() {
        givenPatterns(pattern(ROUTE_GTFS, "143", ALIGHT_GTFS, BOARD_GTFS));
        givenResolvedBus(ROUTE_GTFS, "STOP1", "R1", "143");
        givenSeq("R1", "STOP1", 5);

        assertThat(run().options()).isEmpty();
    }

    @Test
    @DisplayName("승차 정류장이 pattern 에 두 번 이상 나오면(순환·지선) occurrence 불확정 → 제외")
    void excludesRepeatedBoardingStop() {
        givenPatterns(pattern(ROUTE_GTFS, "01A", BOARD_GTFS, ALIGHT_GTFS, BOARD_GTFS));
        givenResolvedBus(ROUTE_GTFS, "STOP1", "R1", "01A");
        givenSeq("R1", "STOP1", 5);

        assertThat(run().options()).isEmpty();
    }

    @Test
    @DisplayName("서울 노선에 매핑되지 않으면(스코프 밖) 제외한다")
    void excludesUnmappedRoute() {
        givenPatterns(pattern(ROUTE_GTFS, "143", BOARD_GTFS, ALIGHT_GTFS));
        when(identityResolver.resolveBus(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(run().options()).isEmpty();
        verify(busResolver, never()).resolveSeq(any(), any());
    }

    @Test
    @DisplayName("승차 순번이 단건이 아니면(0개·여러 개) 제외한다")
    void excludesNonSingleSeq() {
        givenPatterns(pattern(ROUTE_GTFS, "143", BOARD_GTFS, ALIGHT_GTFS));
        givenResolvedBus(ROUTE_GTFS, "STOP1", "R1", "143");
        when(busResolver.resolveSeq("R1", "STOP1"))
                .thenReturn(new TransferStopResolver.SeqResolution(List.of(3, 17)));

        assertThat(run().options()).isEmpty();
    }

    @Test
    @DisplayName("같은 노선의 여러 variant pattern 은 매핑·순번을 한 번만 조회한다(N+1 완화)")
    void dedupesSameRouteBeforeLookup() {
        givenPatterns(
                pattern(ROUTE_GTFS, "143", BOARD_GTFS, "1:BS_MID", ALIGHT_GTFS),
                pattern(ROUTE_GTFS, "143", BOARD_GTFS, ALIGHT_GTFS));
        givenResolvedBus(ROUTE_GTFS, "STOP1", "R1", "143");
        givenSeq("R1", "STOP1", 5);

        assertThat(run().options()).hasSize(1);
        verify(identityResolver, times(1)).resolveBus(any(), eq(ROUTE_GTFS), any());
        verify(busResolver, times(1)).resolveSeq("R1", "STOP1");
    }

    @Test
    @DisplayName("OTP timeout 은 후보 0건이 아니라 TIMEOUT 으로 알린다(소비자가 저장값 폴백)")
    void propagatesTimeout() {
        when(stopPatternPort.patternsThroughStop(any(), any())).thenReturn(StopPatternResult.timeout());

        RerouteRouteOptionSource.SourceOutcome outcome = run();
        assertThat(outcome.status()).isEqualTo(RerouteRouteOptionSource.SourceOutcome.Status.TIMEOUT);
        assertThat(outcome.options()).isEmpty();
    }

    @Test
    @DisplayName("OTP error 는 ERROR 로 알린다")
    void propagatesError() {
        when(stopPatternPort.patternsThroughStop(any(), any())).thenReturn(StopPatternResult.error());

        assertThat(run().status()).isEqualTo(RerouteRouteOptionSource.SourceOutcome.Status.ERROR);
    }

    @Test
    @DisplayName("OTP 정류장 id 가 없으면 조회 불가 — 장애가 아니라 정상 0건(OK)")
    void blankStopIdIsOkEmpty() {
        waitLeg = waitLeg(null, ALIGHT_GTFS);

        RerouteRouteOptionSource.SourceOutcome outcome = run();
        assertThat(outcome.isOk()).isTrue();
        assertThat(outcome.options()).isEmpty();
        verify(stopPatternPort, never()).patternsThroughStop(any(), any());
    }

    // ── 도우미 ─────────────────────────────────────────────────────────────

    private void givenPatterns(StopPattern... patterns) {
        when(stopPatternPort.patternsThroughStop(eq(BOARD_GTFS), any()))
                .thenReturn(StopPatternResult.ok(List.of(patterns)));
    }

    private void givenResolvedBus(String routeGtfs, String stopId, String routeId, String name) {
        when(identityResolver.resolveBus(eq(BOARD_GTFS), eq(routeGtfs), any()))
                .thenReturn(Optional.of(new SeoulBusIdentity(stopId, routeId, name)));
    }

    private void givenSeq(String routeId, String stopId, int seq) {
        when(busResolver.resolveSeq(routeId, stopId))
                .thenReturn(new TransferStopResolver.SeqResolution(List.of(seq)));
    }

    private StopPattern pattern(String routeGtfs, String shortName, String... stops) {
        return new StopPattern(routeGtfs, shortName, -1, List.of(stops));
    }

    private JourneyLeg waitLeg(String sourceStart, String sourceEnd) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(42L)
                .routeRevision(1)
                .legIndex(2)
                .type(JourneyLegType.WAIT_BUS)
                .state(JourneyLegState.PENDING)
                .startStopId("ST-A")
                .sourceStartStopId(sourceStart)
                .sourceEndStopId(sourceEnd)
                .build();
        ReflectionTestUtils.setField(leg, "id", 3L);
        return leg;
    }
}
