package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginKind;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginStop;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;
import watoo.grd.nextroute.domain.bus.repository.RouteStopProjection;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 주행 중 하차 후보 자리 고르기.
 *
 * <p>못박는 것은 넷이다 — <b>실시간이 없으면 자리를 만들지 않는다</b>(시간표 폴백 없음),
 * 리드타임에 못 미치는 정류장은 뺀다, 내 뒤의 차를 내 차로 착각하지 않는다, 그리고 위치를 모르면
 * (진행 관측이 없거나 낡았으면) 창을 아예 열지 않는다.
 */
@DisplayName("주행 중 다음 정류장 창")
class UpcomingStopWindowResolverTest {

    private static final long JOURNEY_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-09-19T09:00:00Z");
    private static final String ROUTE_ID = "BL-RIDE";

    private final BusRouteStopRepository busRouteStopRepository = mock(BusRouteStopRepository.class);
    private final SearchTimeBusQueryPort busQueryPort = mock(SearchTimeBusQueryPort.class);
    private final JourneyRerouteProperties properties = new JourneyRerouteProperties();
    private final JourneyRerouteMetrics metrics = new JourneyRerouteMetrics(new SimpleMeterRegistry());

    private final BusRideLocator locator = new BusRideLocator(busRouteStopRepository);

    private final RegionalRideLocator regionalLocator = org.mockito.Mockito.mock(RegionalRideLocator.class);
    private final RegionalArrivalLookup regionalArrivals = org.mockito.Mockito.mock(RegionalArrivalLookup.class);
    private final UpcomingStopWindowResolver resolver = new UpcomingStopWindowResolver(
            locator, regionalLocator, regionalArrivals, busQueryPort, properties, metrics);

    @BeforeEach
    void setUp() {
        given(busRouteStopRepository.findStopsByRouteId(ROUTE_ID)).willReturn(routeStops());
    }

    @Test
    @DisplayName("리드타임을 넘긴 앞쪽 정류장이 자리가 된다")
    void admitsUpcomingStopsBeyondLeadTime() {
        givenArrival("ST-12", 12, 60);    // 리드타임(90초) 미만 → 뺀다
        givenArrival("ST-13", 13, 300);
        givenArrival("ST-14", 14, 600);

        List<RerouteOriginStop> window = resolver.resolveRideInterior(legs(3), 4, NOW);

        assertThat(window).hasSize(2);
        assertThat(window.get(0).origin().stopId()).isEqualTo("ST-13");
        assertThat(window.get(0).origin().seq()).isEqualTo(13);
        assertThat(window.get(0).origin().kind()).isEqualTo(RerouteOriginKind.RIDE_INTERIOR);
        // 원점이 가리키는 단계는 지금 타고 있는 주행이다.
        assertThat(window.get(0).origin().legIndex()).isEqualTo(3);
        assertThat(window.get(0).alightAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(window.get(0).x()).isEqualTo(127.13);
        assertThat(window.get(1).origin().stopId()).isEqualTo("ST-14");
    }

    @Test
    @DisplayName("계획 하차 정류장은 창에 없다 — 그 자리는 공급자가 이미 들고 있다")
    void plannedAlightIsNotInTheWindow() {
        givenArrival("ST-13", 13, 300);
        givenArrival("ST-14", 14, 600);
        givenArrival("ST-15", 15, 900);

        List<RerouteOriginStop> window = resolver.resolveRideInterior(legs(3), 4, NOW);

        assertThat(window).extracting(stop -> stop.origin().stopId())
                .containsExactly("ST-13", "ST-14");
        verify(busQueryPort, never()).getArrInfoByStop(eq("ST-15"), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("실시간으로 내 차를 못 잡으면 그 정류장은 자리가 아니다 — 시간표로 채우지 않는다")
    void withoutRealtimeTheStopIsDropped() {
        given(busQueryPort.getArrInfoByStop(eq("ST-13"), eq(ROUTE_ID), eq(13), anyLong()))
                .willReturn(SearchTimeBusQueryPort.BusQueryResult.ok(List.of()));
        givenArrival("ST-14", 14, 600);

        List<RerouteOriginStop> window = resolver.resolveRideInterior(legs(3), 4, NOW);

        assertThat(window).extracting(stop -> stop.origin().stopId()).containsExactly("ST-14");
    }

    @Test
    @DisplayName("내 뒤에 오는 차는 내 차가 아니다 — 늦은 도착으로 자리를 만들지 않는다")
    void doesNotPickTheBusBehind() {
        // 내 위치는 12번 구간인데 도착 정보가 준 차는 11번 구간(내 뒤)에 있다.
        given(busQueryPort.getArrInfoByStop(eq("ST-13"), eq(ROUTE_ID), eq(13), anyLong()))
                .willReturn(SearchTimeBusQueryPort.BusQueryResult.ok(
                        List.of(arrival("ST-13", 13, 11, 300))));
        givenArrival("ST-14", 14, 600);

        List<RerouteOriginStop> window = resolver.resolveRideInterior(legs(3), 4, NOW);

        assertThat(window).extracting(stop -> stop.origin().stopId()).containsExactly("ST-14");
    }

    @Test
    @DisplayName("진행 관측이 낡았으면 창을 열지 않는다 — 지나간 정류장을 제안하게 된다")
    void staleProgressOpensNothing() {
        JourneyLeg ride = rideLeg(3, 3, NOW.minusSeconds(3600));

        assertThat(resolver.resolveRideInterior(List.of(ride, waitLeg(4)), 4, NOW)).isEmpty();
        verify(busQueryPort, never()).getArrInfoByStop(anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("남은 정류장 수가 없으면 창을 열지 않는다")
    void withoutRemainingStopsOpensNothing() {
        JourneyLeg ride = rideLeg(3, null, NOW);

        assertThat(resolver.resolveRideInterior(List.of(ride, waitLeg(4)), 4, NOW)).isEmpty();
        verify(busQueryPort, never()).getArrInfoByStop(anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("지하철 주행에는 창을 열지 않는다 — 초기 스코프는 버스만이다")
    void subwayRideOpensNothing() {
        JourneyLeg ride = rideLeg(3, 3, NOW);
        ReflectionTestUtils.setField(ride, "type", JourneyLegType.RIDE_SUBWAY);

        assertThat(resolver.resolveRideInterior(List.of(ride, waitLeg(4)), 4, NOW)).isEmpty();
    }

    @Test
    @DisplayName("주행이 아직 시작되지 않았으면 창을 열지 않는다")
    void pendingRideOpensNothing() {
        JourneyLeg ride = rideLeg(3, 3, NOW);
        ReflectionTestUtils.setField(ride, "state", JourneyLegState.PENDING);

        assertThat(resolver.resolveRideInterior(List.of(ride, waitLeg(4)), 4, NOW)).isEmpty();
    }

    @Test
    @DisplayName("자리 상한과 조회 상한을 지킨다")
    void respectsCaps() {
        properties.getNextStop().setMaxOrigins(1);
        givenArrival("ST-13", 13, 300);
        givenArrival("ST-14", 14, 600);

        assertThat(resolver.resolveRideInterior(legs(3), 4, NOW)).hasSize(1);
        verify(busQueryPort, never()).getArrInfoByStop(eq("ST-14"), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("꺼 두면 창이 아예 열리지 않는다")
    void disabledOpensNothing() {
        properties.getNextStop().setEnabled(false);

        assertThat(resolver.resolveRideInterior(legs(3), 4, NOW)).isEmpty();
        verify(busRouteStopRepository, never()).findStopsByRouteId(anyString());
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    // ── 경기·TAGO(#264 PR-4) ───────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("서울 노선이 아닌 탑승은 보드가 꺼져 있으면 열지 않는다 — 사건 기반 경로는 그대로")
    void regionalRideIsClosedWithoutBoard() {
        JourneyLeg ride = rideLeg(3, 2, NOW);
        ReflectionTestUtils.setField(ride, "routeId", null);

        assertThat(resolver.resolveRideInterior(List.of(ride, waitLeg(4)), 4, NOW, 60)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(regionalLocator);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("보드 모드에서는 경기·TAGO 정류장 표로 중간 정류장을 연다 — 가상점은 건너뛰고 원점에 지역을 싣는다")
    void regionalRideOpensFromCatalog() {
        properties.getBoard().setEnabled(true);
        JourneyLeg ride = rideLeg(3, 2, NOW);
        ReflectionTestUtils.setField(ride, "routeId", null);
        List<watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop> stops = List.of(
                regional("A", 1, false), regional("WP", 2, true), regional("B", 3, false),
                regional("C", 4, false), regional("D", 5, false));
        org.mockito.BDDMockito.given(regionalLocator.locate(org.mockito.ArgumentMatchers.eq(ride),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq(NOW)))
                .willReturn(new RegionalRideLocator.Located(new RegionalRideLocator.Position(
                        watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider.GG, "R",
                        stops, 0, 0, 4, List.of()), null));
        org.mockito.BDDMockito.given(regionalArrivals.arrivalAt(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("R"),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .willReturn(new RegionalArrivalLookup.Lookup(NOW.plusSeconds(300), null));

        List<RerouteOriginStop> window = resolver.resolveRideInterior(List.of(ride, waitLeg(4)), 4, NOW, 60);

        assertThat(window).extracting(stop -> stop.origin().stopId()).containsExactly("B", "C");
        assertThat(window.get(0).origin().provider()).isEqualTo("GG");
        assertThat(window.get(0).origin().seq()).isEqualTo(3);
        // B 까지 남은 API 순번 = 3 - 1(지금 A) = 2.
        org.mockito.Mockito.verify(regionalArrivals).arrivalAt(
                watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider.GG, "R", "B", 2);
    }

    private static watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop regional(
            String id, int seq, boolean waypoint) {
        return new watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop(id, id + "정류장", seq,
                null, null, waypoint, 37.5, 127.0);
    }

    /** 승차 10번, 하차 15번 정류장. 지금은 12번을 지났다(남은 정류장 {@code remainingStops}). */
    private List<JourneyLeg> legs(int remainingStops) {
        return List.of(rideLeg(3, remainingStops, NOW), waitLeg(4));
    }

    private JourneyLeg rideLeg(int legIndex, Integer remainingStops, Instant observedAt) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(JOURNEY_ID).routeRevision(1).legIndex(legIndex)
                .type(JourneyLegType.RIDE_BUS).state(JourneyLegState.ACTIVE)
                .startStopId("ST-10").endStopId("ST-15")
                .startName("정류장10").endName("정류장15")
                .plannedDurationSec(900)
                .build();
        ReflectionTestUtils.setField(leg, "routeId", ROUTE_ID);
        ReflectionTestUtils.setField(leg, "remainingStops", remainingStops);
        ReflectionTestUtils.setField(leg, "progressObservedAt", observedAt);
        return leg;
    }

    private JourneyLeg waitLeg(int legIndex) {
        return JourneyLeg.builder()
                .journeyId(JOURNEY_ID).routeRevision(1).legIndex(legIndex)
                .type(JourneyLegType.WAIT_BUS).state(JourneyLegState.PENDING)
                .startStopId("ST-15").startName("정류장15")
                .build();
    }

    private List<RouteStopProjection> routeStops() {
        List<RouteStopProjection> stops = new ArrayList<>();
        for (int seq = 10; seq <= 16; seq++) {
            stops.add(stop(seq));
        }
        return stops;
    }

    private RouteStopProjection stop(int seq) {
        return new RouteStopProjection() {
            @Override public Integer getSeq() { return seq; }
            @Override public String getStopId() { return "ST-" + seq; }
            @Override public String getStopName() { return "정류장" + seq; }
            @Override public Double getLatitude() { return 37.0 + seq / 100.0; }
            @Override public Double getLongitude() { return 127.0 + seq / 100.0; }
            @Override public String getDirection() { return "방면"; }
        };
    }

    /** 내 차(구간 순번 12 = 지금 내 위치)가 {@code afterSec} 뒤에 그 정류장에 선다. */
    private void givenArrival(String stopId, int seq, int afterSec) {
        given(busQueryPort.getArrInfoByStop(eq(stopId), eq(ROUTE_ID), eq(seq), anyLong()))
                .willReturn(SearchTimeBusQueryPort.BusQueryResult.ok(
                        List.of(arrival(stopId, seq, 12, afterSec))));
    }

    /** 도착 정보 한 건. 시각은 관측 시각({@code dataTimestamp}) + 남은 소요로 만들어진다. */
    private BusArrivalInfo arrival(String stopId, int seq, int sectionOrder, int afterSec) {
        return new BusArrivalInfo(
                ROUTE_ID, "RIDE", "노선", stopId, "01234", "정류장" + seq, seq, "방면", 3, 10,
                observedAt(NOW), "N", "N", "05:00", "23:00", afterSec + "초 후", "VEH-1",
                "서울70사1234", 1, sectionOrder, "직전정류장", "0", "0", "0", afterSec, null, null, null,
                null, null, null, null, afterSec, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    private String observedAt(Instant instant) {
        return DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .format(ZonedDateTime.ofInstant(instant, ZoneId.of("Asia/Seoul")));
    }
}
