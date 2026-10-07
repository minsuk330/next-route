package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.journey.dto.JourneyRouteOptionDraft;
import watoo.grd.nextroute.application.route.dto.LaneGraphicResult;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPatternDetail;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitRoute;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStopTime;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.service.LegacyRouteResponseMapper;
import watoo.grd.nextroute.application.journey.service.StopIdentityResolver;
import watoo.grd.nextroute.domain.bus.repository.BusRouteRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;
import watoo.grd.nextroute.testsupport.InMemoryRegionalBusRouteStopStore;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 패턴 구간 → {@code toSubPath} → 여정 쪼개기. 검색 결과가 받는 보강(실시간 id·지하철 방향)을 단독 추적도 똑같이 받는지
 * 고정한다 — 지하철 {@code wayCode} 가 비면 반대 방향 열차가 후보에 섞이고, 버스 id 가 비면 도착 조회를 못 한다.
 */
@DisplayName("단독 추적 구간 → 여정 단계")
class TrackLegChainTest {

    private static final LocalDate DAY = LocalDate.parse("2026-10-07");

    private final BusStopRepository busStopRepo = mock(BusStopRepository.class);
    private final BusRouteRepository busRouteRepo = mock(BusRouteRepository.class);
    private final LegacyRouteResponseMapper legacy = new LegacyRouteResponseMapper(new OtpSeoulIdentityResolver(
            busStopRepo, busRouteRepo, mock(SubwayStationRepository.class),
            InMemoryRegionalBusRouteStopStore.prefixTableOnlyLookup()));
    private final JourneyCompiler compiler = new JourneyCompiler(new StopIdentityResolver());

    @Test
    @DisplayName("2호선 내선: wayCode 0, 승차역이 stations[0], 대기+탑승 두 단계")
    void subway() {
        RoutingLeg leg = TrackLegFactory.build(TrackFixtures.line2Detail(), 31, 35,
                TrackFixtures.line2Times(), "t", DAY, "g");

        SubPathResult sub = legacy.toSubPath(leg);
        assertThat(sub.trafficType()).isEqualTo(1);
        assertThat(sub.wayCode()).isZero();
        assertThat(sub.stations()).hasSize(5);
        assertThat(sub.stations().get(0).stationName()).isEqualTo(sub.startName());
        assertThat(sub.polyline()).isNotNull();

        JourneyPlan plan = compiler.compile(path(sub));
        assertThat(plan.legs()).extracting(JourneyLegDraft::type)
                .containsExactly(JourneyLegType.WAIT_SUBWAY, JourneyLegType.RIDE_SUBWAY);
        assertThat(plan.transfers()).isEmpty();
        JourneyRouteOptionDraft option = plan.legs().get(0).routeOptions().get(0);
        assertThat(option.wayCode()).isZero();
        assertThat(option.routeName()).isEqualTo("서울2호선");
    }

    @Test
    @DisplayName("서울 버스: 서울 정류장·노선 id 가 실시간 id 로 채워진다")
    void seoulBus() {
        when(busStopRepo.existsByStopId(anyString())).thenReturn(true);
        when(busRouteRepo.existsByRouteId(anyString())).thenReturn(true);
        RoutingLeg leg = TrackLegFactory.build(TrackFixtures.bus640Detail(), 10, 20,
                TrackFixtures.bus640Times(), "t", DAY, "g");

        SubPathResult sub = legacy.toSubPath(leg);
        JourneyPlan plan = compiler.compile(path(sub));

        assertThat(sub.lanes().get(0).busLocalBlID()).isEqualTo("100100093");
        assertThat(sub.startLocalStationID()).matches("\\d{9}");
        JourneyLegDraft wait = plan.legs().get(0);
        assertThat(wait.type()).isEqualTo(JourneyLegType.WAIT_BUS);
        assertThat(wait.sourceStartStopId()).isEqualTo(sub.startLocalStationID());
        assertThat(wait.routeOptions().get(0).sourceRouteId()).isEqualTo("100100093");
        assertThat(plan.legs().get(1).type()).isEqualTo(JourneyLegType.RIDE_BUS);
    }

    @Test
    @DisplayName("경기 버스: GBIS 9자리 id")
    void gyeonggiBus() {
        RoutingLeg leg = TrackLegFactory.build(synthetic("1:BR_TAGO_GGB234000001",
                "1:BS_TAGO_GGB200000001", "1:BS_TAGO_GGB200000002"), 0, 1, times("1:BS_TAGO_GGB200000001",
                "1:BS_TAGO_GGB200000002"), "t", DAY, "g");

        SubPathResult sub = legacy.toSubPath(leg);

        assertThat(sub.lanes().get(0).busLocalBlID()).isEqualTo("234000001");
        assertThat(sub.startLocalStationID()).isEqualTo("200000001");
        assertThat(sub.endLocalStationID()).isEqualTo("200000002");
    }

    @Test
    @DisplayName("지방 TAGO 버스: 접두사 포함 id")
    void tagoBus() {
        RoutingLeg leg = TrackLegFactory.build(synthetic("1:BR_TAGO_DJB30300151",
                "1:BS_TAGO_DJB8001001", "1:BS_TAGO_DJB8001002"), 0, 1, times("1:BS_TAGO_DJB8001001",
                "1:BS_TAGO_DJB8001002"), "t", DAY, "g");

        SubPathResult sub = legacy.toSubPath(leg);

        assertThat(sub.lanes().get(0).busLocalBlID()).isEqualTo("DJB30300151");
        assertThat(sub.startLocalStationID()).isEqualTo("DJB8001001");
    }

    private static PathResult path(SubPathResult sub) {
        return new PathResult(1, new PathInfo(sub.sectionTime(), -1, 0, 0, sub.startName(), sub.endName(), null),
                List.of(sub), List.<LaneGraphicResult>of());
    }

    private static TransitPatternDetail synthetic(String routeId, String boardId, String alightId) {
        TransitRoute route = new TransitRoute(routeId, "301", "-", RoutingMode.BUS, "BUS", null, "1:A1");
        return new TransitPatternDetail("p", route, List.of(
                new TransitStop(boardId, "승차", 36.35, 127.38, RoutingMode.BUS),
                new TransitStop(alightId, "하차", 36.36, 127.39, RoutingMode.BUS)), null);
    }

    private static List<TransitStopTime> times(String boardId, String alightId) {
        return List.of(new TransitStopTime(boardId, 36000, 36000), new TransitStopTime(alightId, 36300, 36300));
    }
}
