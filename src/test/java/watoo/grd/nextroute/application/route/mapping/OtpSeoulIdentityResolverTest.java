package watoo.grd.nextroute.application.route.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;
import watoo.grd.nextroute.domain.bus.repository.BusRouteRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.subway.repository.NearbySubwayStationProjection;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 방법 B(실시간 규칙 resolver) 계약 테스트. OTP GTFS id/좌표 → 서울 provider id.
 * 스코프: 서울 버스(BS_TAGO_SEB) + 지하철 1~9호선만. 그 밖은 empty(STATIC).
 */
class OtpSeoulIdentityResolverTest {

    private final BusStopRepository busStopRepo = mock(BusStopRepository.class);
    private final BusRouteRepository busRouteRepo = mock(BusRouteRepository.class);
    private final SubwayStationRepository subwayRepo = mock(SubwayStationRepository.class);
    private final OtpSeoulIdentityResolver resolver =
            new OtpSeoulIdentityResolver(busStopRepo, busRouteRepo, subwayRepo);

    // 익명 구현체(mockito 중첩 스터빙 회피). 미사용 accessor 는 null.
    private NearbySubwayStationProjection nearby(String statnId, String lineId, double dist) {
        return new NearbySubwayStationProjection() {
            public String getStationId() { return statnId; }
            public String getStationName() { return null; }
            public String getLineId() { return lineId; }
            public String getLineName() { return null; }
            public Double getLatitude() { return null; }
            public Double getLongitude() { return null; }
            public Double getDistMeters() { return dist; }
        };
    }

    @Test
    @DisplayName("버스: SEB prefix strip → stop_id/route_id, DB 존재 → 매핑")
    void busResolved() {
        when(busStopRepo.existsByStopId("101000041")).thenReturn(true);
        when(busRouteRepo.existsByRouteId("100100022")).thenReturn(true);

        Optional<SeoulBusIdentity> r = resolver.resolveBus(
                "1:BS_TAGO_SEB101000041", "1:BR_TAGO_SEB100100022", "143");

        assertThat(r).isPresent();
        assertThat(r.get().stopId()).isEqualTo("101000041");
        assertThat(r.get().routeId()).isEqualTo("100100022");
        assertThat(r.get().routeName()).isEqualTo("143");
    }

    @Test
    @DisplayName("버스: SEB 형식이나 stop DB 미존재 → empty(phantom 매핑 금지)")
    void busStopNotInDb() {
        when(busStopRepo.existsByStopId("999999999")).thenReturn(false);
        Optional<SeoulBusIdentity> r = resolver.resolveBus(
                "1:BS_TAGO_SEB999999999", "1:BR_TAGO_SEB100100022", "143");
        assertThat(r).isEmpty();
    }

    @Test
    @DisplayName("버스: 경기(GGB)·인천(ICB) prefix → empty(스코프 밖=STATIC)")
    void busOutOfScopePrefix() {
        assertThat(resolver.resolveBus("1:BS_TAGO_GGB210000001", "1:BR_TAGO_GGB210000001", "1000"))
                .isEmpty();
        assertThat(resolver.resolveBus("1:BS_TAGO_ICB160000001", "1:BR_TAGO_ICB160000001", "3")).isEmpty();
    }

    @Test
    @DisplayName("지하철: 서울4호선 + 좌표 → 해당 라인(1004) 최근접 statn_id")
    void subwayResolved() {
        when(subwayRepo.findNearby(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of(
                        nearby("1002000205", "1002", 4.0),   // 다른 라인(2호선), 더 가깝지만 라인 불일치
                        nearby("1004000427", "1004", 6.0)));  // 4호선 = 목표

        Optional<SeoulSubwayIdentity> r = resolver.resolveSubway("서울4호선", 37.544618, 126.972135);

        assertThat(r).isPresent();
        assertThat(r.get().stationId()).isEqualTo("1004000427");
        assertThat(r.get().lineId()).isEqualTo("1004");
    }

    @Test
    @DisplayName("지하철: 호선번호 1~9 아님(경의중앙/신분당) → empty(스코프 밖)")
    void subwayOutOfScopeLine() {
        assertThat(resolver.resolveSubway("수도권경의·중앙선", 37.5, 127.0)).isEmpty();
        assertThat(resolver.resolveSubway("신분당선", 37.5, 127.0)).isEmpty();
    }

    @Test
    @DisplayName("지하철: 반경 내 목표 라인 후보 없음 → empty(STATIC)")
    void subwayNoLineMatchInRadius() {
        when(subwayRepo.findNearby(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of(nearby("1002000205", "1002", 4.0)));
        assertThat(resolver.resolveSubway("서울4호선", 37.544618, 126.972135)).isEmpty();
    }

    @Test
    @DisplayName("dispatch: WALK/미지원 leg → empty")
    void dispatchNonTransit() {
        RoutingLeg walk = new RoutingLeg(RoutingMode.WALK, null, "1", null, null, null, null, null,
                null, null, 0, List.of(), List.of(), null, null, null);
        StopOccurrence stop = new StopOccurrence("x", null, null, 0, 127.0, 37.5, "x");
        assertThat(resolver.resolve(walk, stop)).isEmpty();
    }

    @Test
    @DisplayName("dispatch: BUS leg → SeoulBusIdentity")
    void dispatchBus() {
        when(busStopRepo.existsByStopId("101000041")).thenReturn(true);
        when(busRouteRepo.existsByRouteId("100100022")).thenReturn(true);
        StopOccurrence boarding = new StopOccurrence("1:BS_TAGO_SEB101000041", null, null, 0, 127.0, 37.5, "s");
        RoutingLeg bus = new RoutingLeg(RoutingMode.BUS, null, "1", "A1",
                "1:BR_TAGO_SEB100100022", "143", "trip", null, null, null, 0,
                List.of(boarding), List.of(), null, null, null);
        assertThat(resolver.resolve(bus, boarding))
                .get().isInstanceOf(SeoulBusIdentity.class);
    }

    @Test
    @DisplayName("dispatch: SUBWAY leg → SeoulSubwayIdentity(좌표+호선)")
    void dispatchSubway() {
        when(subwayRepo.findNearby(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of(nearby("1004000427", "1004", 3.0)));
        StopOccurrence boarding = new StopOccurrence("1:RS_ACC1_S-1-0427", null, null, 0, 126.972135, 37.544618, "s");
        RoutingLeg sub = new RoutingLeg(RoutingMode.SUBWAY, null, "1", "A1",
                "1:RR_ACC1_S-1-04-1D", "서울4호선", "trip", null, null, null, 0,
                List.of(boarding), List.of(), null, null, null);
        assertThat(resolver.resolve(sub, boarding))
                .get().isInstanceOf(SeoulSubwayIdentity.class);
    }

    @Test
    @DisplayName("지하철: 두 자리 호선(11호선) 뒷자리 오매치 방지 → empty")
    void subwayTwoDigitLineRejected() {
        assertThat(resolver.resolveSubway("11호선", 37.5, 127.0)).isEmpty();
    }
}
