package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.application.route.mapping.BusRealtimeStopIds;
import watoo.grd.nextroute.testsupport.InMemoryRegionalBusRouteStopStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeoPoint;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeometryQuality;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.StopGeometry;
import watoo.grd.nextroute.application.journey.dto.ManeuverType;
import watoo.grd.nextroute.application.journey.exception.StaleRouteRevisionException;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.dto.LaneResult;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.dto.WalkStep;
import watoo.grd.nextroute.domain.journey.entity.JourneyEndReason;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevision;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevisionStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyRevisionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;
import watoo.grd.nextroute.domain.bus.repository.RouteStopProjection;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("여정 좌표를 단계 종류별 원천에서 모은다")
class JourneyNavigationGeometryServiceTest {

    private static final long JOURNEY_ID = 7L;
    private static final long USER_ID = 42L;
    private static final int REVISION = 1;

    private JourneySessionRepository sessionRepository;
    private JourneyRevisionRepository revisionRepository;
    private JourneyLegRepository legRepository;
    private JourneyLegRouteOptionRepository routeOptionRepository;
    private BusRouteStopRepository busRouteStopRepository;
    private JourneyNavigationGeometryService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        sessionRepository = mock(JourneySessionRepository.class);
        revisionRepository = mock(JourneyRevisionRepository.class);
        legRepository = mock(JourneyLegRepository.class);
        routeOptionRepository = mock(JourneyLegRouteOptionRepository.class);
        busRouteStopRepository = mock(BusRouteStopRepository.class);
        service = new JourneyNavigationGeometryService(
                sessionRepository, revisionRepository, legRepository, routeOptionRepository,
                new BusShapeStopSplitter(),
                busRouteStopRepository,
                new JourneyCompiler(new StopIdentityResolver()),
                new GeometrySegmentAllocator(),
                new WalkManeuverAnchorer(new GeometrySegmentAllocator(),
                        new WalkManeuverTypeMapper()),
                objectMapper,
                new BusRealtimeStopIds(InMemoryRegionalBusRouteStopStore.prefixTableOnlyLookup()),
                watoo.grd.nextroute.application.subway.service.SubwayExpressRouteFixtures.railServiceClassifier());
    }

    @Test
    @DisplayName("버스 단계는 검색 결과 선(shapes.txt)을 정류장 위치에서 잘라 정류장 구간으로 내려준다")
    void cutsSnapshotShapeAtStops() {
        givenJourney(walkThenShapedBus());
        JourneyLeg wait = leg(1, JourneyLegType.WAIT_BUS, null);
        JourneyLeg ride = leg(2, JourneyLegType.RIDE_BUS, 1200);
        givenLegs(leg(0, JourneyLegType.WALK, 300), wait, ride);
        givenOptions(option(wait.getId(), "R1", 10, 13));
        List<RouteStopProjection> routeStops = List.of(
                routeStop(9, "S9", "범위 밖", 37.49, 126.99),
                routeStop(10, "S10", "승차 정류장", 37.5, 127.001),
                routeStop(11, "S11", "중간 정류장", 37.5001, 127.0102),
                routeStop(12, "S12", "좌표 없는 정류장", null, null),
                routeStop(13, "S13", "하차 정류장", 37.5, 127.02),
                routeStop(14, "S14", "범위 밖", 37.52, 127.03));
        when(busRouteStopRepository.findStopsByRouteId("R1")).thenReturn(routeStops);

        LegGeometry busLeg = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(2);

        // 좌표 없는 정류장은 자르는 데 쓰지 않고 앞 구간에 묻힌다.
        assertThat(busLeg.segments()).extracting(s -> s.fromSeq())
                .containsExactly(10, 11);
        assertThat(busLeg.segments()).extracting(s -> s.toSeq())
                .containsExactly(11, 13);
        assertThat(busLeg.segments()).extracting(s -> s.quality())
                .containsOnly(GeometryQuality.OK);
        assertThat(busLeg.quality()).isEqualTo(GeometryQuality.OK);
        // 미리보기와 같은 선: shapes.txt 꼭짓점을 모두 지나고 양 끝이 같다.
        assertThat(busLeg.points()).containsSubsequence(
                new GeoPoint(127.001, 37.5), new GeoPoint(127.005, 37.5),
                new GeoPoint(127.01, 37.5), new GeoPoint(127.015, 37.5), new GeoPoint(127.02, 37.5));
        assertThat(busLeg.points().get(0)).isEqualTo(new GeoPoint(127.001, 37.5));
        assertThat(busLeg.points().get(busLeg.points().size() - 1)).isEqualTo(new GeoPoint(127.02, 37.5));
        assertThat(busLeg.routeId()).isEqualTo("R1");
        assertThat(busLeg.stops()).extracting(stop -> stop.seq())
                .containsExactly(10, 11, 12, 13);
    }

    @Test
    @DisplayName("검색 결과에 선이 없으면 정류장끼리 직선으로 잇고 APPROXIMATE 로 둔다")
    void fallsBackToStopLinesWithoutShape() {
        givenJourney(walkThenBus());
        JourneyLeg wait = leg(1, JourneyLegType.WAIT_BUS, null);
        givenLegs(leg(0, JourneyLegType.WALK, 300), wait, leg(2, JourneyLegType.RIDE_BUS, 1200));
        givenOptions(option(wait.getId(), "R1", 10, 11));
        List<RouteStopProjection> routeStops = List.of(
                routeStop(10, "S10", "승차", 37.5, 127.001),
                routeStop(11, "S11", "하차", 37.51, 127.002));
        when(busRouteStopRepository.findStopsByRouteId("R1")).thenReturn(routeStops);

        LegGeometry busLeg = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(2);

        assertThat(busLeg.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.fromSeq()).isEqualTo(10);
            assertThat(segment.toSeq()).isEqualTo(11);
            assertThat(segment.quality()).isEqualTo(GeometryQuality.APPROXIMATE);
        });
        assertThat(busLeg.points()).containsExactly(
                new GeoPoint(127.001, 37.5), new GeoPoint(127.002, 37.51));
    }

    @Test
    @DisplayName("타는 단계는 선택된 lane 의 노선 색을, 도보·대기는 null 을 싣는다")
    void ridesCarryTheSelectedLaneColor() {
        givenJourney(walkThenColoredBus());
        JourneyLeg wait = leg(1, JourneyLegType.WAIT_BUS, null);
        givenLegs(leg(0, JourneyLegType.WALK, 300), wait, leg(2, JourneyLegType.RIDE_BUS, 1200));
        JourneyLegRouteOption second = option(wait.getId(), "R2", null, null);
        ReflectionTestUtils.setField(second, "laneIndex", 1);
        givenOptions(second);

        List<LegGeometry> legs = service.get(USER_ID, JOURNEY_ID, REVISION).legs();

        assertThat(legs).extracting(LegGeometry::displayColor)
                .containsExactly(null, null, "#53B332");
    }

    @Test
    @DisplayName("선택 lane 에 색이 없거나 형식이 틀리면 색이 있는 첫 lane 을 쓴다")
    void fallsBackToFirstColoredLane() {
        givenJourney(walkThenColoredBus());
        JourneyLeg wait = leg(1, JourneyLegType.WAIT_BUS, null);
        givenLegs(leg(0, JourneyLegType.WALK, 300), wait, leg(2, JourneyLegType.RIDE_BUS, 1200));
        JourneyLegRouteOption third = option(wait.getId(), "R3", null, null);
        ReflectionTestUtils.setField(third, "laneIndex", 2);
        givenOptions(third);

        LegGeometry bus = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(2);

        assertThat(bus.displayColor()).isEqualTo("#0068B7");
    }

    // ── subway-express S2: allStops·버전 키 ─────────────────────────────────

    /** 9호선 급행 가양→염창(증미·등촌 통과). 좌표는 운영 OTP fixture. */
    private PathResult line9Express(String trainKind) {
        SubPathResult subway = SubPathFixture.subway(4).names("가양", "염창")
                .coords(126.854271, 37.561558, 126.874771, 37.546974)
                .stations(
                        new StationResult(0, "1:G", "가양", 126.854271, 37.561558),
                        new StationResult(1, "1:Y", "염창", 126.874771, 37.546974))
                .lanes(new LaneResult("서울9호선", null, null, null, null, null, "#BDB092"))
                .trainKind(trainKind)
                .build();
        return new PathResult(1, null, List.of(subway), List.of());
    }

    @Test
    @DisplayName("급행 지하철 단계: stops 는 계획 정차열 그대로, allStops 는 완행 전체 역에 통과역 trainStops=false")
    void expressSubwayAllStops() throws Exception {
        givenJourney(line9Express("EXPRESS"));
        givenLegs(leg(0, JourneyLegType.RIDE_SUBWAY, 240));
        givenOptions();

        JourneyNavigationGeometry geometry = service.get(USER_ID, JOURNEY_ID, REVISION);
        JourneyNavigationGeometry.LegGeometry ride = geometry.legs().get(0);

        assertThat(ride.stops()).extracting(StopGeometry::stopName).containsExactly("가양", "염창");
        assertThat(ride.stops()).extracting(StopGeometry::trainStops).containsOnlyNulls();
        assertThat(ride.allStops()).extracting(StopGeometry::stopName).containsExactly("가양", "증미", "등촌", "염창");
        assertThat(ride.allStops()).extracting(StopGeometry::trainStops).containsExactly(true, false, false, true);
        assertThat(ride.allStops()).extracting(StopGeometry::seq).containsExactly(0, 1, 2, 3);
        assertThat(ride.allStops()).extracting(StopGeometry::stopId).containsOnlyNulls();
        assertThat(ride.allStops().get(1).x()).isNotNull();
        assertThat(ride.displayColor()).isEqualTo("#BDB092");

        String json = objectMapper.writeValueAsString(geometry);
        assertThat(json).contains("\"geometryVersion\":\"1:2026-10-21\"");
        // 구버전 앱이 세는 stops 에는 새 칸이 나가지 않는다
        com.fasterxml.jackson.databind.JsonNode leg = objectMapper.readTree(json).path("legs").get(0);
        assertThat(leg.path("stops").get(0).has("trainStops")).isFalse();
        assertThat(leg.path("allStops").get(1).path("trainStops").asBoolean(true)).isFalse();
    }

    @Test
    @DisplayName("일반·모름 지하철 단계: allStops 는 계획 정차열에 전부 trainStops=true, 다른 단계는 allStops 가 없다")
    void normalSubwayAndOtherLegs() throws Exception {
        givenJourney(subwayWithStations());
        givenLegs(leg(0, JourneyLegType.RIDE_SUBWAY, 300));
        givenOptions();

        JourneyNavigationGeometry.LegGeometry ride = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(0);

        assertThat(ride.allStops()).extracting(StopGeometry::stopName).containsExactly("성수", "건대입구", "구의");
        assertThat(ride.allStops()).extracting(StopGeometry::trainStops).containsOnly(true);

        givenJourney(walkWithPolyline());
        givenLegs(leg(0, JourneyLegType.WALK, 300));
        JourneyNavigationGeometry walkOnly = service.get(USER_ID, JOURNEY_ID, REVISION);
        assertThat(walkOnly.legs().get(0).allStops()).isNull();
        assertThat(objectMapper.writeValueAsString(walkOnly)).doesNotContain("allStops");
    }

    @Test
    @DisplayName("급행인데 완행 순서를 모르면(서울 노선 아님) 계획 정차열 그대로 — 세는 값은 같다")
    void expressUnknownLineFallsBack() {
        SubPathResult subway = SubPathFixture.subway(4).names("A", "B")
                .stations(new StationResult(0, "1:A", "A", 127.0, 37.5), new StationResult(1, "1:B", "B", 127.1, 37.5))
                .lanes(new LaneResult("부산1호선", null, null, null, null, null, null)).trainKind("EXPRESS").build();
        givenJourney(new PathResult(1, null, List.of(subway), List.of()));
        givenLegs(leg(0, JourneyLegType.RIDE_SUBWAY, 240));
        givenOptions();

        JourneyNavigationGeometry.LegGeometry ride = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(0);

        assertThat(ride.allStops()).extracting(StopGeometry::stopName).containsExactly("A", "B");
        assertThat(ride.allStops()).extracting(StopGeometry::trainStops).containsOnly(true);
    }

    @Test
    @DisplayName("지하철 단계는 lane 색을 싣는다")
    void subwayCarriesLaneColor() {
        SubPathResult subway = SubPathFixture.subway(6).names("성수", "구의")
                .coords(127.0, 37.5, 127.02, 37.5)
                .lanes(new LaneResult("서울2호선", null, 2, null, null, null, "#00A23F"))
                .build();
        givenJourney(new PathResult(1, null, List.of(subway), List.of()));
        givenLegs(leg(0, JourneyLegType.RIDE_SUBWAY, 300));
        givenOptions();

        assertThat(service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(0).displayColor())
                .isEqualTo("#00A23F");
    }

    @Test
    @DisplayName("노선·정류장 순번을 해석하지 못한 버스 단계는 실시간 조회를 하지 않는다")
    void skipsLookupWhenBusScopeUnresolved() {
        givenJourney(walkThenBus());
        JourneyLeg wait = leg(1, JourneyLegType.WAIT_BUS, null);
        givenLegs(leg(0, JourneyLegType.WALK, 300), wait, leg(2, JourneyLegType.RIDE_BUS, 1200));
        // 순번 미해석 후보
        givenOptions(option(wait.getId(), "R1", null, null));

        service.get(USER_ID, JOURNEY_ID, REVISION);

        verify(busRouteStopRepository, never()).findStopsByRouteId(anyString());
    }

    @Test
    @DisplayName("#258 서울 노선표에 없는 버스(경기 M4130)는 검색 결과 정류장으로 정류장·구간을 채운다")
    void unresolvedBusUsesSnapshotStops() {
        // 2026-10-06 여정 110 실측: 서울역버스환승센터 → 명동입구, M버스라 중간 정차 없음. 후보는 노선·순번 미해석.
        SubPathResult bus = SubPathFixture.bus(10).names("서울역버스환승센터(6번승강장)(중)", "명동입구")
                .coords(126.972733, 37.555149, 126.982597, 37.564682)
                .busStops("101000006", "101000148")
                .lanes(SubPathFixture.busLane("M4130", "234001577"))
                .stations(
                        new StationResult(0, "1:BS_TAGO_GGB101000006", "서울역버스환승센터(6번승강장)(중)",
                                126.972733, 37.555149),
                        new StationResult(1, "1:BS_TAGO_GGB101000148", "명동입구", 126.982597, 37.564682))
                .polyline(new CoordPoint(126.972733, 37.555149), new CoordPoint(126.977, 37.56),
                        new CoordPoint(126.982597, 37.564682))
                .build();
        givenJourney(new PathResult(1, null, List.of(walkOnly(), bus), List.of()));
        JourneyLeg wait = leg(1, JourneyLegType.WAIT_BUS, null);
        givenLegs(leg(0, JourneyLegType.WALK, 300), wait, leg(2, JourneyLegType.RIDE_BUS, 600));
        givenOptions(option(wait.getId(), null, null, null));

        LegGeometry busLeg = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(2);

        assertThat(busLeg.stops()).extracting(s -> s.seq(), s -> s.stopId(), s -> s.stopName())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, "101000006", "서울역버스환승센터(6번승강장)(중)"),
                        org.assertj.core.groups.Tuple.tuple(1, "101000148", "명동입구"));
        assertThat(busLeg.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.fromSeq()).isZero();
            assertThat(segment.toSeq()).isEqualTo(1);
        });
        assertThat(busLeg.quality()).isEqualTo(GeometryQuality.OK);
        assertThat(busLeg.points()).hasSize(3);
        verify(busRouteStopRepository, never()).findStopsByRouteId(anyString());
    }

    @Test
    @DisplayName("#258 지방(TAGO) 버스는 접두사 포함 id 로, 중간 정류장까지 순서대로 싣는다 — 섞여 와도 정렬")
    void unresolvedRegionalBusKeepsPrefixedIdsInOrder() {
        SubPathResult bus = SubPathFixture.bus(15).names("영도대교", "부산역")
                .coords(129.036, 35.095, 129.040, 35.115)
                .busStops("BSB167850202", "BSB505850000")
                .lanes(SubPathFixture.busLane("186", "BSB5200186000"))
                .stations(
                        new StationResult(2, "1:BS_TAGO_BSB505850000", "부산역", 129.040, 35.115),
                        new StationResult(0, "1:BS_TAGO_BSB167850202", "영도대교", 129.036, 35.095),
                        new StationResult(1, "1:BS_TAGO_BSB999", "중간", 129.038, 35.105))
                .build();
        givenJourney(new PathResult(1, null, List.of(walkOnly(), bus), List.of()));
        JourneyLeg wait = leg(1, JourneyLegType.WAIT_BUS, null);
        givenLegs(leg(0, JourneyLegType.WALK, 300), wait, leg(2, JourneyLegType.RIDE_BUS, 900));
        givenOptions();   // 후보 자체가 없음

        LegGeometry busLeg = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(2);

        assertThat(busLeg.stops()).extracting(s -> s.stopId())
                .containsExactly("BSB167850202", "BSB999", "BSB505850000");
        assertThat(busLeg.segments()).extracting(s -> s.fromSeq()).containsExactly(0, 1);
        // 선이 없어 정류장 직선 — 실측이 아니므로 OK 가 아니다
        assertThat(busLeg.quality()).isEqualTo(GeometryQuality.APPROXIMATE);
    }

    @Test
    @DisplayName("#258 검색 결과에 정류장 목록이 없으면 승차·하차 두 곳이라도 싣는다")
    void unresolvedBusWithoutStationsUsesBoardAndAlight() {
        SubPathResult bus = SubPathFixture.bus(10).names("승차", "하차")
                .coords(127.001, 37.5, 127.02, 37.5)
                .busStops("228000191", "104000279")
                .lanes(SubPathFixture.busLane("5600", "228000184"))
                .build();
        givenJourney(new PathResult(1, null, List.of(walkOnly(), bus), List.of()));
        JourneyLeg wait = leg(1, JourneyLegType.WAIT_BUS, null);
        givenLegs(leg(0, JourneyLegType.WALK, 300), wait, leg(2, JourneyLegType.RIDE_BUS, 600));
        givenOptions(option(wait.getId(), null, null, null));

        LegGeometry busLeg = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(2);

        assertThat(busLeg.stops()).extracting(s -> s.seq(), s -> s.stopId(), s -> s.stopName())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, "228000191", "승차"),
                        org.assertj.core.groups.Tuple.tuple(1, "104000279", "하차"));
        assertThat(busLeg.segments()).isNotEmpty();
    }

    @Test
    @DisplayName("#258 정류장을 하나도 못 구하면 선은 주되 품질을 OK 가 아닌 값으로 낮춘다")
    void unresolvedBusWithoutAnyStopIsDegraded() {
        SubPathResult bus = SubPathFixture.bus(10)
                .lanes(SubPathFixture.busLane("M4130", "234001577"))
                .polyline(new CoordPoint(127.001, 37.5), new CoordPoint(127.02, 37.5))
                .build();
        givenJourney(new PathResult(1, null, List.of(walkOnly(), bus), List.of()));
        JourneyLeg wait = leg(1, JourneyLegType.WAIT_BUS, null);
        givenLegs(leg(0, JourneyLegType.WALK, 300), wait, leg(2, JourneyLegType.RIDE_BUS, 600));
        givenOptions(option(wait.getId(), null, null, null));

        LegGeometry busLeg = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(2);

        assertThat(busLeg.stops()).isEmpty();
        assertThat(busLeg.points()).hasSize(2);
        assertThat(busLeg.quality()).isNotEqualTo(GeometryQuality.OK);
        assertThat(busLeg.segments()).extracting(s -> s.quality()).doesNotContain(GeometryQuality.OK);
    }

    private SubPathResult walkOnly() {
        return SubPathFixture.walk(5).names("집", "정류장").coords(127.0, 37.5, 127.001, 37.5).build();
    }

    @Test
    @DisplayName("같은 노선을 여러 단계에서 사용해도 정류장은 요청당 한 번만 조회한다")
    void cachesStopsByRouteWithinRequest() {
        givenJourney(walkThenBus());
        JourneyLeg wait1 = leg(0, JourneyLegType.WAIT_BUS, null);
        JourneyLeg ride1 = leg(1, JourneyLegType.RIDE_BUS, 600);
        JourneyLeg wait2 = leg(2, JourneyLegType.WAIT_BUS, null);
        JourneyLeg ride2 = leg(3, JourneyLegType.RIDE_BUS, 600);
        givenLegs(wait1, ride1, wait2, ride2);
        givenOptions(option(wait1.getId(), "R1", 10, 12), option(wait2.getId(), "R1", 12, 14));
        List<RouteStopProjection> routeStops = List.of(
                routeStop(10, "S10", "A", 37.5, 127.0),
                routeStop(12, "S12", "B", 37.51, 127.01),
                routeStop(14, "S14", "C", 37.52, 127.02));
        when(busRouteStopRepository.findStopsByRouteId("R1")).thenReturn(routeStops);

        JourneyNavigationGeometry result = service.get(USER_ID, JOURNEY_ID, REVISION);

        assertThat(result.legs().get(1).stops()).extracting(stop -> stop.seq())
                .containsExactly(10, 12);
        assertThat(result.legs().get(3).stops()).extracting(stop -> stop.seq())
                .containsExactly(12, 14);
        verify(busRouteStopRepository, times(1)).findStopsByRouteId("R1");
    }

    @Test
    @DisplayName("검색 결과 좌표가 있으면 걷기·지하철은 그 좌표로 구간을 만든다")
    void usesSnapshotPolylineForWalk() {
        givenJourney(walkWithPolyline());
        givenLegs(leg(0, JourneyLegType.WALK, 300));
        givenOptions();

        LegGeometry walk = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(0);

        assertThat(walk.quality()).isEqualTo(GeometryQuality.OK);
        assertThat(walk.points()).hasSize(3);
        assertThat(walk.segments()).hasSize(2);
        assertThat(walk.totalDistanceMeters()).isGreaterThan(0.0);
        assertThat(walk.segments().stream()
                .mapToInt(s -> s.plannedDurationSeconds())
                .sum()).isEqualTo(300);
    }

    @Test
    @DisplayName("좌표가 없으면 직선으로 채우고 품질을 APPROXIMATE 로 낮춘다")
    void fallsBackToStraightLine() {
        // TMAP 보강을 건너뛴 검색 결과. polyline 이 비어 있다.
        givenJourney(walkThenBus());
        givenLegs(leg(0, JourneyLegType.WALK, 300));
        givenOptions();

        LegGeometry walk = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(0);

        assertThat(walk.quality()).isEqualTo(GeometryQuality.APPROXIMATE);
        assertThat(walk.points()).hasSize(2);
        assertThat(walk.segments()).hasSize(1);
        assertThat(walk.segments().get(0).qualityReason()).isEqualTo("NO_SOURCE_GEOMETRY");
        assertThat(walk.segments().get(0).plannedDurationSeconds()).isEqualTo(300);
    }

    @Test
    @DisplayName("기다리는 단계는 한 지점만 주고 진행 판정 구간을 만들지 않는다")
    void waitLegHasNoSegments() {
        givenJourney(walkThenBus());
        givenLegs(leg(0, JourneyLegType.WALK, 300), leg(1, JourneyLegType.WAIT_BUS, null));
        givenOptions();

        LegGeometry wait = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(1);

        assertThat(wait.segments()).isEmpty();
        assertThat(wait.points()).hasSize(1);
    }

    @Test
    @DisplayName("걷기 단계는 얼려 둔 안내점을 폴리라인 위 위치로 바꿔 함께 내려준다")
    void addsManeuversToWalkLeg() {
        givenJourney(walkWithManeuvers());
        givenLegs(leg(0, JourneyLegType.WALK, 300));
        givenOptions();

        LegGeometry walk = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(0);

        assertThat(walk.maneuvers()).hasSize(3);
        assertThat(walk.maneuvers()).extracting(m -> m.maneuverIndex()).containsExactly(0, 1, 2);
        assertThat(walk.maneuvers()).extracting(m -> m.type())
                .containsExactly(ManeuverType.DEPART, ManeuverType.LEFT, ManeuverType.ARRIVE);
        assertThat(walk.maneuvers().get(1).instruction()).isEqualTo("횡단보도를 건넌 후 좌회전하세요");
        // 구간 순번은 이 단계 segments 의 순번이어야 한다.
        assertThat(walk.maneuvers()).allSatisfy(m ->
                assertThat(m.geometrySegmentIndex()).isBetween(0, walk.segments().size() - 1));
        assertThat(walk.maneuvers()).extracting(m -> m.distanceFromLegStartMeters()).isSorted();
        assertThat(walk.maneuvers().get(2).distanceFromLegStartMeters())
                .isLessThanOrEqualTo(walk.totalDistanceMeters());
        // 좌표·구간 계약은 그대로다.
        assertThat(walk.points()).hasSize(3);
        assertThat(walk.segments()).hasSize(2);
    }

    @Test
    @DisplayName("환승 도보도 같은 안내점을 받는다")
    void addsManeuversToTransferWalkLeg() {
        givenJourney(walkWithManeuvers());
        givenLegs(leg(0, JourneyLegType.TRANSFER_WALK, 300));
        givenOptions();

        LegGeometry walk = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(0);

        assertThat(walk.type()).isEqualTo(JourneyLegType.TRANSFER_WALK);
        assertThat(walk.maneuvers()).hasSize(3);
    }

    @Test
    @DisplayName("타고 가는 단계와 기다리는 단계는 안내점을 주지 않는다")
    void transitLegsHaveNoManeuvers() {
        givenJourney(walkThenBus());
        JourneyLeg wait = leg(1, JourneyLegType.WAIT_BUS, null);
        JourneyLeg ride = leg(2, JourneyLegType.RIDE_BUS, 1200);
        givenLegs(leg(0, JourneyLegType.WALK, 300), wait, ride);
        givenOptions(option(wait.getId(), "R1", 10, 11));
        List<RouteStopProjection> routeStops = List.of(
                routeStop(10, "S10", "승차", 37.5, 127.001),
                routeStop(11, "S11", "하차", 37.51, 127.002));
        when(busRouteStopRepository.findStopsByRouteId("R1")).thenReturn(routeStops);

        JourneyNavigationGeometry result = service.get(USER_ID, JOURNEY_ID, REVISION);

        assertThat(result.legs()).allSatisfy(legGeometry ->
                assertThat(legGeometry.maneuvers()).isNotNull());
        assertThat(result.legs().get(1).maneuvers()).isEmpty();
        assertThat(result.legs().get(2).maneuvers()).isEmpty();
        // 버스 단계는 정류장을 잃지 않는다.
        assertThat(result.legs().get(2).stops()).hasSize(2);
    }

    @Test
    @DisplayName("지하철 단계는 좌표만 주고 안내점은 비운다")
    void subwayLegHasNoManeuvers() {
        givenJourney(walkWithPolyline());
        givenLegs(leg(0, JourneyLegType.RIDE_SUBWAY, 300));
        givenOptions();

        LegGeometry subway = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(0);

        assertThat(subway.segments()).hasSize(2);
        assertThat(subway.maneuvers()).isEmpty();
    }

    @Test
    @DisplayName("지하철 단계는 지나는 역을 순서대로 내려준다")
    void subwayLegCarriesItsStationChain() {
        givenJourney(subwayWithStations());
        givenLegs(leg(0, JourneyLegType.RIDE_SUBWAY, 300));
        givenOptions();

        LegGeometry subway = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(0);

        assertThat(subway.stops())
                .extracting(StopGeometry::stopName)
                .containsExactly("성수", "건대입구", "구의");
    }

    @Test
    @DisplayName("검색 결과가 역 순서를 섞어 주더라도 순번대로 다시 세운다")
    void subwayStationsAreSortedBySequence() {
        SubPathResult subway = SubPathFixture.subway(10).names("성수", "구의")
                .coords(127.0, 37.5, 127.02, 37.5)
                .polyline(new CoordPoint(127.0, 37.5), new CoordPoint(127.02, 37.5))
                .stations(
                        new StationResult(2, "S3", "구의", 127.02, 37.5),
                        new StationResult(0, "S1", "성수", 127.0, 37.5),
                        new StationResult(1, "S2", "건대입구", 127.01, 37.5))
                .build();
        givenJourney(new PathResult(1, null, List.of(subway), List.of()));
        givenLegs(leg(0, JourneyLegType.RIDE_SUBWAY, 300));
        givenOptions();

        LegGeometry leg = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(0);

        assertThat(leg.stops())
                .extracting(StopGeometry::seq)
                .containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("지하철 역에는 정류장 식별자를 싣지 않는다 — 실시간 쪽과 체계가 다르다")
    void subwayStationsCarryNoStopId() {
        givenJourney(subwayWithStations());
        givenLegs(leg(0, JourneyLegType.RIDE_SUBWAY, 300));
        givenOptions();

        LegGeometry subway = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(0);

        assertThat(subway.stops())
                .isNotEmpty()
                .allSatisfy(stop -> assertThat(stop.stopId()).isNull());
    }

    @Test
    @DisplayName("재탐색 뒤에는 새 개정본의 역 목록을 준다 — 세션에 남은 옛 경로가 아니다")
    void subwayStationsFollowTheCurrentRevision() {
        JourneySession journey = session();
        // 세션에는 여정을 시작할 때의 경로가 남아 있다. 재탐색이 바꾼 것은 개정본 쪽이다.
        ReflectionTestUtils.setField(journey, "routeSnapshot",
                objectMapper.valueToTree(subwayWithStations()));
        ReflectionTestUtils.setField(journey, "routeRevision", 2);
        when(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(JOURNEY_ID, USER_ID))
                .thenReturn(Optional.of(journey));
        givenRevisionSnapshot(2, reroutedSubway());
        when(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                JOURNEY_ID, 2)).thenReturn(List.of(leg(0, JourneyLegType.RIDE_SUBWAY, 300)));
        givenOptions();

        LegGeometry subway = service.get(USER_ID, JOURNEY_ID, 2).legs().get(0);

        assertThat(subway.stops())
                .extracting(StopGeometry::stopName)
                .containsExactly("왕십리", "한양대");
    }

    @Test
    @DisplayName("검색 결과에 역 목록이 없으면 빈 목록이고 좌표는 그대로다")
    void subwayWithoutStationsStillHasGeometry() {
        givenJourney(walkWithPolyline());
        givenLegs(leg(0, JourneyLegType.RIDE_SUBWAY, 300));
        givenOptions();

        LegGeometry subway = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(0);

        assertThat(subway.stops()).isEmpty();
        assertThat(subway.segments()).isNotEmpty();
    }

    @Test
    @DisplayName("안내점이 없는 도보는 좌표만 그대로 주고 안내점을 빈 목록으로 둔다")
    void walkWithoutStepsHasEmptyManeuvers() {
        givenJourney(walkWithPolyline());
        givenLegs(leg(0, JourneyLegType.WALK, 300));
        givenOptions();

        LegGeometry walk = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(0);

        assertThat(walk.maneuvers()).isEmpty();
        assertThat(walk.points()).hasSize(3);
        assertThat(walk.segments()).hasSize(2);
        assertThat(walk.quality()).isEqualTo(GeometryQuality.OK);
    }

    @Test
    @DisplayName("직선으로 대체한 도보에는 안내점을 얹지 않는다 — 없는 길을 가리키게 된다")
    void straightLineWalkHasNoManeuvers() {
        // 안내점은 있지만 폴리라인이 없어 직선으로 떨어지는 검색 결과.
        SubPathResult walk = SubPathFixture.walk(5).names("집", "정류장")
                .coords(127.0, 37.5, 127.002, 37.5)
                .walkSteps(walkStep(0, "SP", 200, 127.0, 37.5),
                        walkStep(1, "EP", 201, 127.002, 37.5))
                .build();
        givenJourney(new PathResult(1, null, List.of(walk), List.of()));
        givenLegs(leg(0, JourneyLegType.WALK, 300));
        givenOptions();

        LegGeometry result = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(0);

        assertThat(result.quality()).isEqualTo(GeometryQuality.APPROXIMATE);
        assertThat(result.maneuvers()).isEmpty();
    }

    @Test
    @DisplayName("이전 개정본을 가리키면 현재 개정본과 함께 충돌로 거절한다")
    void rejectsStaleRevision() {
        givenJourney(walkThenBus());

        assertThatThrownBy(() -> service.get(USER_ID, JOURNEY_ID, REVISION + 1))
                .isInstanceOf(StaleRouteRevisionException.class)
                .satisfies(e -> assertThat(((StaleRouteRevisionException) e).getCurrentRouteRevision())
                        .isEqualTo(REVISION));
    }

    @Test
    @DisplayName("개정본을 생략하면 현재 개정본을 준다")
    void defaultsToCurrentRevision() {
        givenJourney(walkThenBus());
        givenLegs(leg(0, JourneyLegType.WALK, 300));
        givenOptions();

        assertThat(service.get(USER_ID, JOURNEY_ID, null).routeRevision()).isEqualTo(REVISION);
    }

    @Test
    @DisplayName("route snapshot 이 깨져도 예외를 던지지 않고 직선으로 내려간다")
    void survivesBrokenSnapshot() {
        JourneySession journey = session();
        ReflectionTestUtils.setField(journey, "routeSnapshot",
                objectMapper.createObjectNode().put("깨진", "모양"));
        when(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(JOURNEY_ID, USER_ID))
                .thenReturn(Optional.of(journey));
        givenLegs(leg(0, JourneyLegType.WALK, 300));
        givenOptions();

        LegGeometry walk = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(0);

        // 원본 구간을 못 찾으니 양 끝 좌표도 없다. 진행은 막지 않고 품질만 알린다.
        assertThat(walk.quality()).isEqualTo(GeometryQuality.FAILED);
        assertThat(walk.segments()).isEmpty();
        assertThat(walk.maneuvers()).isEmpty();
    }

    @Test
    @DisplayName("없는 여정과 남의 여정을 구분하지 않는다")
    void hidesOwnership() {
        when(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(USER_ID, JOURNEY_ID, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(JourneyErrorCode.JOURNEY_NOT_FOUND));
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    // ── 좌표 원천 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("좌표 원천은 그 개정본의 스냅샷이다 — 세션 스냅샷은 시작 시점 원본이라 영원히 안 바뀐다")
    void drawsFromRevisionSnapshot() {
        givenJourney(walkThenBus());
        // 개정본은 다른 경로를 들고 있다. 재탐색을 수락한 뒤의 모습이다.
        givenRevisionSnapshot(REVISION, movedWalk());
        givenLegs(leg(0, JourneyLegType.WALK, 300));

        JourneyNavigationGeometry result = service.get(USER_ID, JOURNEY_ID, REVISION);

        assertThat(result.legs().get(0).segments().get(0).points())
                .extracting(GeoPoint::x)
                .as("세션 스냅샷(127.0)이 아니라 개정본 스냅샷(128.0)을 그린다")
                .startsWith(128.0);
    }

    @Test
    @DisplayName("1번 개정본에 행이 없으면 세션 스냅샷으로 그린다 — 그때만 두 값이 같다")
    void fallsBackToSessionSnapshotOnFirstRevision() {
        givenJourney(walkThenBus());
        when(revisionRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(JOURNEY_ID, 1))
                .thenReturn(Optional.empty());
        givenLegs(leg(0, JourneyLegType.WALK, 300));

        JourneyNavigationGeometry result = service.get(USER_ID, JOURNEY_ID, 1);

        assertThat(result.legs().get(0).segments().get(0).points())
                .extracting(GeoPoint::x)
                .startsWith(127.0);
    }

    @Test
    @DisplayName("2번 개정본에 행이 없으면 실패한다 — 조용히 옛 경로를 그리지 않는다")
    void failsWhenLaterRevisionSnapshotIsMissing() {
        JourneySession journey = session();
        ReflectionTestUtils.setField(journey, "routeSnapshot",
                objectMapper.valueToTree(walkThenBus()));
        ReflectionTestUtils.setField(journey, "routeRevision", 2);
        when(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(JOURNEY_ID, USER_ID))
                .thenReturn(Optional.of(journey));
        when(revisionRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(JOURNEY_ID, 2))
                .thenReturn(Optional.empty());
        when(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                JOURNEY_ID, 2)).thenReturn(List.of(leg(0, JourneyLegType.WALK, 300)));

        assertThatThrownBy(() -> service.get(USER_ID, JOURNEY_ID, 2))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 세션 스냅샷과 다른 경로. 개정본 쪽이 그려지는지 가리기 위한 값이다. */
    private PathResult movedWalk() {
        return new PathResult(1, null, List.of(SubPathFixture.walk(5).names("집", "정류장")
                .coords(128.0, 38.5, 128.001, 38.5).build()), List.of());
    }

    private void givenRevisionSnapshot(int revision, PathResult path) {
        JourneyRevision entity = JourneyRevision.builder()
                .journeyId(JOURNEY_ID)
                .routeRevision(revision)
                .status(JourneyRevisionStatus.ACTIVE)
                .routeSnapshot(objectMapper.valueToTree(path))
                .activatedAt(Instant.parse("2026-08-04T00:00:00Z"))
                .build();
        when(revisionRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(
                JOURNEY_ID, revision)).thenReturn(Optional.of(entity));
    }

    private void givenJourney(PathResult path) {
        JourneySession journey = session();
        ReflectionTestUtils.setField(journey, "routeSnapshot", objectMapper.valueToTree(path));
        when(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(JOURNEY_ID, USER_ID))
                .thenReturn(Optional.of(journey));
    }

    private JourneySession session() {
        JourneySession journey = JourneySession.builder()
                .userId(USER_ID)
                .startedAt(Instant.parse("2026-08-04T00:00:00Z"))
                .guidanceLeaseUntil(Instant.parse("2026-08-04T00:03:00Z"))
                .build();
        ReflectionTestUtils.setField(journey, "id", JOURNEY_ID);
        return journey;
    }

    private void givenLegs(JourneyLeg... legs) {
        when(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                JOURNEY_ID, REVISION)).thenReturn(List.of(legs));
    }

    private void givenOptions(JourneyLegRouteOption... options) {
        when(routeOptionRepository.findByJourneyLegIdInAndDeletedAtIsNull(any()))
                .thenReturn(List.of(options));
    }

    private JourneyLeg leg(int index, JourneyLegType type, Integer plannedSeconds) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(JOURNEY_ID)
                .routeRevision(REVISION)
                .legIndex(index)
                .type(type)
                .state(index == 0 ? JourneyLegState.ACTIVE : JourneyLegState.PENDING)
                .plannedDurationSec(plannedSeconds)
                .build();
        ReflectionTestUtils.setField(leg, "id", 100L + index);
        return leg;
    }

    private JourneyLegRouteOption option(Long legId, String routeId,
                                         Integer boardingSeq, Integer alightingSeq) {
        JourneyLegRouteOption option = JourneyLegRouteOption.builder()
                .journeyLegId(legId)
                .optionIndex(0)
                .laneIndex(0)
                .routeId(routeId)
                .sourceRouteId("BL" + routeId)
                .routeName("370")
                .boardingSeq(boardingSeq)
                .alightingSeq(alightingSeq)
                .build();
        ReflectionTestUtils.setField(option, "id", 900L);
        return option;
    }

    private RouteStopProjection routeStop(Integer seq, String stopId, String stopName,
                                          Double latitude, Double longitude) {
        RouteStopProjection stop = mock(RouteStopProjection.class);
        when(stop.getSeq()).thenReturn(seq);
        when(stop.getStopId()).thenReturn(stopId);
        when(stop.getStopName()).thenReturn(stopName);
        when(stop.getLatitude()).thenReturn(latitude);
        when(stop.getLongitude()).thenReturn(longitude);
        return stop;
    }

    /** 걷기 → 버스. 좌표 보강이 없어 polyline 이 비어 있는 경로다. */
    private PathResult walkThenBus() {
        SubPathResult walk = SubPathFixture.walk(5).names("집", "정류장")
                .coords(127.0, 37.5, 127.001, 37.5).build();
        SubPathResult bus = SubPathFixture.bus(20).names("정류장", "회사앞")
                .coords(127.001, 37.5, 127.02, 37.5)
                .busStops("100", "200")
                .lanes(SubPathFixture.busLane("370", "BL370"))
                .build();
        return new PathResult(1, null, List.of(walk, bus), List.of());
    }

    /** 걷기 → shapes.txt 선이 실린 버스. 꼭짓점은 승차(127.001)부터 하차(127.02)까지 직선 위에 있다. */
    private PathResult walkThenShapedBus() {
        SubPathResult walk = SubPathFixture.walk(5).names("집", "정류장")
                .coords(127.0, 37.5, 127.001, 37.5).build();
        SubPathResult bus = SubPathFixture.bus(20).names("정류장", "회사앞")
                .coords(127.001, 37.5, 127.02, 37.5)
                .busStops("100", "200")
                .lanes(SubPathFixture.busLane("370", "BL370"))
                .polyline(new CoordPoint(127.001, 37.5), new CoordPoint(127.005, 37.5),
                        new CoordPoint(127.01, 37.5), new CoordPoint(127.015, 37.5),
                        new CoordPoint(127.02, 37.5))
                .build();
        return new PathResult(1, null, List.of(walk, bus), List.of());
    }

    /** 걷기 → 색이 다른 lane 셋을 가진 버스. 세 번째 lane 색은 형식이 틀렸다. */
    private PathResult walkThenColoredBus() {
        SubPathResult walk = SubPathFixture.walk(5).names("집", "정류장")
                .coords(127.0, 37.5, 127.001, 37.5).build();
        SubPathResult bus = SubPathFixture.bus(20).names("정류장", "회사앞")
                .coords(127.001, 37.5, 127.02, 37.5)
                .busStops("100", "200")
                .lanes(new LaneResult("302", "302", null, null, null, "BL302", "#0068B7"),
                        new LaneResult("성동03-2", "성동03-2", null, null, null, "BL03", "#53B332"),
                        new LaneResult("370", "370", null, null, null, "BL370", "0068B7"))
                .build();
        return new PathResult(1, null, List.of(walk, bus), List.of());
    }

    /** TMAP 보강이 붙은 걷기 하나. */
    /** 재탐색이 갈아 끼운 지하철 구간. 세션에 남은 것과 역이 겹치지 않아야 가릴 수 있다. */
    private PathResult reroutedSubway() {
        SubPathResult subway = SubPathFixture.subway(6).names("왕십리", "한양대")
                .coords(127.03, 37.56, 127.04, 37.55)
                .polyline(new CoordPoint(127.03, 37.56), new CoordPoint(127.04, 37.55))
                .stations(
                        new StationResult(0, "S9", "왕십리", 127.03, 37.56),
                        new StationResult(1, "S10", "한양대", 127.04, 37.55))
                .build();
        return new PathResult(1, null, List.of(subway), List.of());
    }

    /** 역 목록이 실린 지하철 구간. 검색 결과가 passStopList 를 주는 정상 경우다. */
    private PathResult subwayWithStations() {
        SubPathResult subway = SubPathFixture.subway(10).names("성수", "구의")
                .coords(127.0, 37.5, 127.02, 37.5)
                .polyline(new CoordPoint(127.0, 37.5),
                        new CoordPoint(127.01, 37.5),
                        new CoordPoint(127.02, 37.5))
                .stations(
                        new StationResult(0, "S1", "성수", 127.0, 37.5),
                        new StationResult(1, "S2", "건대입구", 127.01, 37.5),
                        new StationResult(2, "S3", "구의", 127.02, 37.5))
                .build();
        return new PathResult(1, null, List.of(subway), List.of());
    }

    private PathResult walkWithPolyline() {
        SubPathResult walk = SubPathFixture.walk(5).names("집", "정류장")
                .coords(127.0, 37.5, 127.002, 37.5)
                .polyline(new CoordPoint(127.0, 37.5),
                        new CoordPoint(127.001, 37.5),
                        new CoordPoint(127.002, 37.5))
                .build();
        return new PathResult(1, null, List.of(walk), List.of());
    }

    /** TMAP 보강이 폴리라인과 안내점을 함께 붙인 걷기. 실제 응답처럼 안내점 좌표는 폴리라인 정점이다. */
    private PathResult walkWithManeuvers() {
        SubPathResult walk = SubPathFixture.walk(5).names("집", "정류장")
                .coords(127.0, 37.5, 127.002, 37.5)
                .polyline(new CoordPoint(127.0, 37.5),
                        new CoordPoint(127.001, 37.5),
                        new CoordPoint(127.002, 37.5))
                .walkSteps(
                        walkStep(0, "SP", 200, 127.0, 37.5),
                        walkStep(1, "GP", 12, 127.001, 37.5),
                        walkStep(2, "EP", 201, 127.002, 37.5))
                .build();
        return new PathResult(1, null, List.of(walk), List.of());
    }

    private WalkStep walkStep(int index, String pointType, Integer turnType, double x, double y) {
        String description = switch (pointType) {
            case "SP" -> "출발";
            case "EP" -> "도착";
            default -> "횡단보도를 건넌 후 좌회전하세요";
        };
        return new WalkStep(index, pointType, turnType, description, x, y);
    }
}
