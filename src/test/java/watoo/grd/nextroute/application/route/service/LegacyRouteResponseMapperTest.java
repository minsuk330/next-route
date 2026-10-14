package watoo.grd.nextroute.application.route.service;

import watoo.grd.nextroute.testsupport.InMemoryRegionalBusRouteStopStore;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver;
import watoo.grd.nextroute.application.route.routing.Enrichment;
import watoo.grd.nextroute.application.route.routing.GeometryQuality;
import watoo.grd.nextroute.application.route.routing.GeometrySource;
import watoo.grd.nextroute.application.route.routing.LegGeometry;
import watoo.grd.nextroute.application.route.routing.RealtimeCoverage;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.route.routing.RoutingWalkStep;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;
import watoo.grd.nextroute.domain.bus.repository.BusRouteRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B2/B3 계약: polyline 은 대중교통 {@code SHAPE_OK} 일 때만, 도보는 항상(OTP 실경로) 채운다.
 * 그 외(STREET_FALLBACK/미커버 지하철)는 null(가짜 직선 금지) + 2점 미만은 null(B1 fail-closed 연동).
 */
class LegacyRouteResponseMapperTest {

    // otp/planconnection-success.json 첫 BUS leg 형상(16pts, 롯데백화점 37.5645,126.982 → 고속터미널 37.5058,127.004)
    private static final String BUS_SHAPE =
            "cygdFod`fWfTfE??nPoK??rrB_X??pK???ls@_X??r]gE??jgBwj@??nKg^";
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

    private final BusStopRepository busStopRepository = mock(BusStopRepository.class);
    private final BusRouteRepository busRouteRepository = mock(BusRouteRepository.class);
    private final LegacyRouteResponseMapper mapper = new LegacyRouteResponseMapper(
            new OtpSeoulIdentityResolver(busStopRepository, busRouteRepository,
                    mock(SubwayStationRepository.class),
                    InMemoryRegionalBusRouteStopStore.prefixTableOnlyLookup()));

    private RoutingLeg transitLeg(RoutingMode mode, GeometryQuality quality, String points) {
        return transitLeg(mode, quality, points, "1:R1", "143");
    }

    private RoutingLeg transitLeg(RoutingMode mode, GeometryQuality quality, String points,
                                  String sourceRouteId, String sourceRouteName) {
        LegGeometry geometry = new LegGeometry(
                quality == GeometryQuality.SHAPE_OK ? GeometrySource.GTFS_SHAPE : GeometrySource.OTP_STREET,
                quality, "test fixture", "g1", points, points);
        String sourceStopId = mode == RoutingMode.BUS ? "1:BS_TAGO_SEB100100001" : "1:S1";
        List<StopOccurrence> stops = List.of(
                new StopOccurrence(sourceStopId, null, null, 0, 126.982, 37.5645, "from"),
                new StopOccurrence(sourceStopId, null, null, 1, 127.004, 37.5058, "to"));
        return new RoutingLeg(mode, null, "1", "1:A1", sourceRouteId, sourceRouteName,
                "1:T1", null, null, null, 600, stops, List.of(),
                geometry, Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), null);
    }

    /** 그래프가 route_color 를 준 leg(OTP route.color 원문, "#" 없음). */
    private RoutingLeg coloredLeg(RoutingMode mode, String sourceRouteId, String sourceRouteName, String color) {
        RoutingLeg base = transitLeg(mode, GeometryQuality.STREET_FALLBACK, BUS_SHAPE, sourceRouteId,
                sourceRouteName);
        return new RoutingLeg(base.mode(), base.sourceDetailType(), base.feedId(), base.agencyId(),
                base.sourceRouteId(), base.sourceRouteName(), base.sourceRouteDirection(), base.tripId(),
                base.serviceDate(), base.boardAt(), base.alightAt(), base.rideDurationSeconds(), base.stops(),
                base.walkSteps(), base.geometry(), base.enrichment(), base.missing(), color);
    }

    private RoutingLeg subwayLeg(String routeShortName, String direction) {
        LegGeometry geometry = new LegGeometry(GeometrySource.OTP_STREET,
                GeometryQuality.STREET_FALLBACK, "test fixture", "g1", BUS_SHAPE, BUS_SHAPE);
        List<StopOccurrence> stops = List.of(
                new StopOccurrence("1:S1", null, null, 0, 126.982, 37.5645, "from"),
                new StopOccurrence("1:S2", null, null, 1, 127.004, 37.5058, "to"));
        return new RoutingLeg(RoutingMode.SUBWAY, null, "1", "1:A1", "1:R1", routeShortName,
                direction, "1:T1", null, null, null, 600, stops, List.of(),
                geometry, Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), null);
    }

    private RoutingLeg walkLeg(String points) {
        LegGeometry geometry = new LegGeometry(GeometrySource.OTP_STREET, GeometryQuality.STREET_FALLBACK,
                "OTP street walk", null, points, points);
        return new RoutingLeg(RoutingMode.WALK, null, null, null, null, null,
                null, null, null, null, 120, List.of(), List.of(),
                geometry, Enrichment.staticOnly(RealtimeCoverage.NONE), null);
    }

    private SubPathResult firstSubPath(RoutingLeg leg) {
        return firstSubPath(mapper, leg);
    }

    private static SubPathResult firstSubPath(LegacyRouteResponseMapper mapper, RoutingLeg leg) {
        RoutingItinerary itinerary = new RoutingItinerary(NOW, 600, 0, 0, 0, false, null, null, List.of(leg));
        RoutingResult result = new RoutingResult(RoutingProvider.OTP, "v2", "net1", "g1", null, null,
                NOW, List.of(), List.of(itinerary));
        RouteSearchResult legacy = mapper.toLegacy(result);
        return legacy.paths().get(0).subPaths().get(0);
    }

    /** 거리·turn 을 지정한 도보 leg. */
    private RoutingLeg walkLeg(Double distanceMeters, List<RoutingWalkStep> steps) {
        LegGeometry geometry = new LegGeometry(GeometrySource.OTP_STREET, GeometryQuality.STREET_FALLBACK,
                "OTP street walk", null, BUS_SHAPE, BUS_SHAPE);
        return new RoutingLeg(RoutingMode.WALK, null, null, null, null, null, null,
                null, null, null, null, 120, List.of(), steps,
                geometry, Enrichment.staticOnly(RealtimeCoverage.NONE), null, null, distanceMeters);
    }

    @Test
    @DisplayName("도보 거리를 채운다 — 구간 distance 는 leg 거리 반올림, totalWalk 는 도보 구간 합(#399)")
    void walkDistanceAndTotalWalk() {
        RoutingItinerary itinerary = new RoutingItinerary(NOW, 600, 0, 0, 0, false, null, null, List.of(
                walkLeg(853.57, List.of()),
                transitLeg(RoutingMode.BUS, GeometryQuality.SHAPE_OK, BUS_SHAPE),
                walkLeg(null, List.of(new RoutingWalkStep(100.4, "DEPART", "a"),
                        new RoutingWalkStep(10.2, "LEFT", "b"))),        // leg 거리 없음 → turn 합 110.6
                walkLeg(null, List.of())));                              // 둘 다 없음 → null, 합에서 빠짐
        RoutingResult result = new RoutingResult(RoutingProvider.OTP, "v2", "net1", "g1", null, null,
                NOW, List.of(), List.of(itinerary));

        var path = mapper.toLegacy(result).paths().get(0);

        assertThat(path.subPaths()).extracting(SubPathResult::distance)
                .containsExactly(854.0, null, 111.0, null);
        assertThat(path.info().totalWalk()).isEqualTo(854 + 111);
    }

    @Test
    @DisplayName("SHAPE_OK 버스 leg → polyline 채움(디코드 좌표, x=lon/y=lat)")
    void shapeOkBusFilled() {
        SubPathResult sub = firstSubPath(transitLeg(RoutingMode.BUS, GeometryQuality.SHAPE_OK, BUS_SHAPE));
        assertThat(sub.polyline()).isNotNull().hasSize(16);
        assertThat(sub.polyline().get(0).y()).isCloseTo(37.5645, Offset.offset(0.001));
        assertThat(sub.polyline().get(0).x()).isCloseTo(126.982, Offset.offset(0.001));
    }

    @Test
    @DisplayName("STREET_FALLBACK 버스 leg → polyline null(미검증 형상은 가짜 직선 금지)")
    void streetFallbackBusNull() {
        SubPathResult sub = firstSubPath(transitLeg(RoutingMode.BUS, GeometryQuality.STREET_FALLBACK, BUS_SHAPE));
        assertThat(sub.polyline()).isNull();
    }

    @Test
    @DisplayName("지하철 leg(현행 STREET_FALLBACK, Stage1 미커버) → polyline null")
    void subwayNotCoveredNull() {
        SubPathResult sub = firstSubPath(transitLeg(RoutingMode.SUBWAY, GeometryQuality.STREET_FALLBACK, BUS_SHAPE));
        assertThat(sub.polyline()).isNull();
    }

    @Test
    @DisplayName("도보 leg → OTP 실경로 항상 채움(대중교통 quality 게이트 대상 아님)")
    void walkAlwaysFilled() {
        String walkShape = "cwhdFcq_fW@s@?[F?j@B@?H?T@l@@~@@d@?n@@dABjBDR?hBHV?LHLJRJVL?{C@yBD@?QDK@oEF??"
                + "y@J?v@AJA@w@@KDAHCF?ZD@GpBd@?E";
        SubPathResult sub = firstSubPath(walkLeg(walkShape));
        assertThat(sub.polyline()).isNotNull();
        assertThat(sub.polyline().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("SHAPE_OK 라도 디코드 1점 미만이면 null(선은 2점부터 의미, B1 fail-closed 연동)")
    void tooShortBecomesNull() {
        SubPathResult sub = firstSubPath(transitLeg(RoutingMode.BUS, GeometryQuality.SHAPE_OK, "_p~iF~ps|U"));
        assertThat(sub.polyline()).isNull();
    }

    @Test
    @DisplayName("지하철 lane 색은 그래프 route_color 를 그대로 쓴다(\"#\" 접두)")
    void subwayLaneUsesRouteColor() {
        SubPathResult sub = firstSubPath(coloredLeg(RoutingMode.SUBWAY, "1:RR_ACC1_B1", "부산1호선", "F06A00"));

        assertThat(sub.lanes()).singleElement().satisfies(lane -> {
            assertThat(lane.name()).isEqualTo("부산1호선");
            assertThat(lane.routeDisplayColor()).isEqualTo("#F06A00");
        });
    }

    @Test
    @DisplayName("색이 없어도 지하철 lane(노선명)은 내려간다 — 노선이 이름 없는 지하철로 그려지면 안 된다")
    void subwayLaneWithoutColorKeepsName() {
        SubPathResult sub = firstSubPath(transitLeg(RoutingMode.SUBWAY, GeometryQuality.STREET_FALLBACK,
                BUS_SHAPE, "1:RR_ACC1_X", "새노선"));

        assertThat(sub.lanes()).singleElement().satisfies(lane -> {
            assertThat(lane.name()).isEqualTo("새노선");
            assertThat(lane.routeDisplayColor()).isNull();
        });
    }

    @ParameterizedTest(name = "{0} {1} → wayCode {2}")
    @MethodSource("subwayDirections")
    @DisplayName("지하철 방향 라벨을 wayCode 로 옮긴다 — 앱이 실시간 도착을 한쪽으로 거를 수 있어야 한다")
    void subwayDirectionBecomesWayCode(String routeShortName, String direction, Integer expected) {
        SubPathResult sub = firstSubPath(subwayLeg(routeShortName, direction));

        assertThat(sub.wayCode()).isEqualTo(expected);
    }

    static Stream<Arguments> subwayDirections() {
        return Stream.of(
                Arguments.of("서울1호선", "상행", 1),
                Arguments.of("서울1호선", "하행", 2),
                // 순환선은 내·외선으로 말한다. 노선 번호가 아니라 라벨이 값 공간을 정한다.
                Arguments.of("서울2호선", "내선", 0),
                Arguments.of("서울2호선", "외선", 1),
                // 방향을 못 읽은 leg 은 null 이다 — 지어내면 반대 방향 열차에 고정된다.
                Arguments.of("서울3호선", null, null),
                Arguments.of("서울3호선", "동쪽", null)
        );
    }

    @Test
    @DisplayName("버스 leg 에는 방향을 채우지 않는다 — 원천에도 없다")
    void busHasNoWayCode() {
        SubPathResult sub = firstSubPath(transitLeg(RoutingMode.BUS, GeometryQuality.STREET_FALLBACK,
                BUS_SHAPE));

        assertThat(sub.wayCode()).isNull();
    }

    @Test
    @DisplayName("버스 lane 색은 그래프 route_color 를 쓰고, 서울 실시간 id 매핑과는 무관하다")
    void busLaneUsesRouteColor() {
        String routeId = "100100001";
        when(busStopRepository.existsByStopId(routeId)).thenReturn(true);
        when(busRouteRepository.existsByRouteId(routeId)).thenReturn(true);

        SubPathResult sub = firstSubPath(coloredLeg(RoutingMode.BUS, "1:BR_TAGO_SEB" + routeId, "143", "0068B7"));

        assertThat(sub.lanes()).singleElement().satisfies(lane -> {
            assertThat(lane.busLocalBlID()).isEqualTo(routeId);
            assertThat(lane.routeDisplayColor()).isEqualTo("#0068B7");
        });
    }

    @Test
    @DisplayName("서울 외 버스도 그래프가 준 색을 그대로 쓴다(서울 DB 조회 없음)")
    void nonSeoulBusUsesRouteColor() {
        SubPathResult sub = firstSubPath(coloredLeg(RoutingMode.BUS, "1:BR_TAGO_BSB5200000", "83", "53B332"));

        assertThat(sub.lanes()).singleElement().satisfies(lane -> {
            assertThat(lane.busLocalBlID()).isNull();
            assertThat(lane.routeDisplayColor()).isEqualTo("#53B332");
        });
    }

    @Test
    @DisplayName("공항버스(_U/_D 접미사)도 서울 노선 id 로 실시간 매핑한다")
    void airportBusSuffixStillMapsSeoulRoute() {
        String routeId = "100100419";
        when(busStopRepository.existsByStopId("100100001")).thenReturn(true);
        when(busRouteRepository.existsByRouteId(routeId)).thenReturn(true);

        SubPathResult sub = firstSubPath(coloredLeg(RoutingMode.BUS, "1:BR_TAGO_SEB" + routeId + "_U", "6012",
                "E60012"));

        assertThat(sub.lanes()).singleElement().satisfies(lane -> {
            assertThat(lane.busLocalBlID()).isEqualTo(routeId);
            assertThat(lane.routeDisplayColor()).isEqualTo("#E60012");
        });
    }

    /** 잠실역.잠실대교남단(중) → 테크노마트앞.강변역(C), 5600(경기). 2026-09-29 실 응답 id. */
    private RoutingLeg gyeonggiBusLeg(String sourceRouteId) {
        LegGeometry geometry = new LegGeometry(GeometrySource.OTP_STREET,
                GeometryQuality.STREET_FALLBACK, "test fixture", "g1", BUS_SHAPE, BUS_SHAPE);
        List<StopOccurrence> stops = List.of(
                new StopOccurrence("1:BS_TAGO_GGB123000001", null, null, 0, 127.0985, 37.5155, "잠실역.잠실대교남단(중)"),
                new StopOccurrence("1:BS_TAGO_GGB104000279", null, null, 1, 127.0937, 37.5369, "테크노마트앞.강변역(C)"));
        return new RoutingLeg(RoutingMode.BUS, null, "1", "1:A1", sourceRouteId, "5600",
                "1:T1", null, null, null, 600, stops, List.of(),
                geometry, Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), null);
    }

    @Test
    @DisplayName("경기 마을버스(노선 1:BR_3100_)도 GBIS id 로 채운다 — TAGO 로 새지 않는다")
    void gyeonggiVillageBusFillsGbisIds() {
        SubPathResult sub = firstSubPath(gyeonggiBusLeg("1:BR_3100_241419002"));

        assertThat(sub.startLocalStationID()).isEqualTo("123000001");
        assertThat(sub.endLocalStationID()).isEqualTo("104000279");
        assertThat(sub.lanes()).singleElement()
                .satisfies(lane -> assertThat(lane.busLocalBlID()).isEqualTo("241419002"));
    }

    @Test
    @DisplayName("경기 버스(GGB)는 GBIS id 로 승·하차 정류장과 노선을 채운다 — 서울 DB 에 없어도")
    void gyeonggiBusFillsGbisIds() {
        SubPathResult sub = firstSubPath(gyeonggiBusLeg("1:BR_TAGO_GGB228000184"));

        assertThat(sub.startLocalStationID()).isEqualTo("123000001");
        assertThat(sub.endLocalStationID()).isEqualTo("104000279");
        assertThat(sub.lanes()).singleElement().satisfies(lane -> {
            assertThat(lane.busNo()).isEqualTo("5600");
            assertThat(lane.busLocalBlID()).isEqualTo("228000184");
        });
    }

    @Test
    @DisplayName("서울 노선은 서울 DB 매핑이 우선이고 경기 해석으로 새지 않는다")
    void seoulBusKeepsSeoulIds() {
        when(busStopRepository.existsByStopId("100100001")).thenReturn(true);
        when(busRouteRepository.existsByRouteId("100100209")).thenReturn(true);

        SubPathResult sub = firstSubPath(transitLeg(RoutingMode.BUS, GeometryQuality.STREET_FALLBACK, BUS_SHAPE,
                "1:BR_TAGO_SEB100100209", "2412"));

        assertThat(sub.startLocalStationID()).isEqualTo("100100001");
        assertThat(sub.lanes()).singleElement()
                .satisfies(lane -> assertThat(lane.busLocalBlID()).isEqualTo("100100209"));
    }

    private RoutingLeg tagoBusLeg(String boardStop, String alightStop, String sourceRouteId, String name) {
        LegGeometry geometry = new LegGeometry(GeometrySource.OTP_STREET,
                GeometryQuality.STREET_FALLBACK, "test fixture", "g1", BUS_SHAPE, BUS_SHAPE);
        List<StopOccurrence> stops = List.of(
                new StopOccurrence(boardStop, null, null, 0, 129.036, 35.095, "from"),
                new StopOccurrence(alightStop, null, null, 1, 129.040, 35.100, "to"));
        return new RoutingLeg(RoutingMode.BUS, null, "1", "1:A1", sourceRouteId, name,
                "1:T1", null, null, null, 600, stops, List.of(),
                geometry, Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), null);
    }

    @Test
    @DisplayName("서울·경기 밖 버스(부산)는 접두사 포함 TAGO id 로 승·하차 정류장과 노선을 채운다")
    void tagoBusFillsPrefixedIds() {
        SubPathResult sub = firstSubPath(tagoBusLeg(
                "1:BS_TAGO_BSB167850202", "1:BS_TAGO_BSB167850201", "1:BR_TAGO_BSB5200186000", "186"));

        assertThat(sub.startLocalStationID()).isEqualTo("BSB167850202");
        assertThat(sub.endLocalStationID()).isEqualTo("BSB167850201");
        assertThat(sub.lanes()).singleElement()
                .satisfies(lane -> assertThat(lane.busLocalBlID()).isEqualTo("BSB5200186000"));
    }

    @Test
    @DisplayName("도시코드 표 밖(농어촌 TSB)은 id 를 비운다 — 프론트가 호출하지 않게")
    void tagoBusOutsideCityTableStaysNull() {
        SubPathResult sub = firstSubPath(tagoBusLeg(
                "1:BS_TAGO_TSB309000122", "1:BS_TAGO_TSB309000123", "1:BR_TAGO_TSB309000001", "농어촌"));

        assertThat(sub.startLocalStationID()).isNull();
        assertThat(sub.endLocalStationID()).isNull();
        assertThat(sub.lanes()).singleElement().satisfies(lane -> assertThat(lane.busLocalBlID()).isNull());
    }

    @Test
    @DisplayName("광역급행 M버스(M+4자리)는 그래프 기본색(초록) 대신 광역 빨강 — 경기 M4130·인천 M6405·예약 노선")
    void metropolitanExpressBusIsRed() {
        for (String[] c : new String[][]{
                {"1:BR_TAGO_GGB234001577", "M4130"},
                {"1:BR_TAGO_ICB165000370", "M6405"},
                {"1:BR_TAGO_GGB228000999", "M5443(예약)"}}) {
            SubPathResult sub = firstSubPath(coloredLeg(RoutingMode.BUS, c[0], c[1], "53B332"));
            assertThat(sub.lanes()).singleElement()
                    .satisfies(lane -> assertThat(lane.routeDisplayColor()).as(c[1]).isEqualTo("#E60012"));
        }
    }

    @Test
    @DisplayName("M 으로 시작해도 3자리 이하(경기 마을 M101·세종 M1)나 일반 번호는 그래프 색 그대로(유형 모르는 노선)")
    void otherBusesKeepGraphColor() {
        for (String name : new String[]{"M101", "M1", "5600", "M41301"}) {
            SubPathResult sub = firstSubPath(coloredLeg(RoutingMode.BUS, "1:BR_TAGO_SJB293000001", name, "53B332"));
            assertThat(sub.lanes()).singleElement()
                    .satisfies(lane -> assertThat(lane.routeDisplayColor()).as(name).isEqualTo("#53B332"));
        }
    }

    @Test
    @DisplayName("#391 경기 버스는 노선 유형 색 — 직행좌석 4403·9202·8502 빨강, M4403 빨강(번들 GBIS 목록 실데이터)")
    void gyeonggiBusUsesRouteTypeColor() {
        for (String[] c : new String[][]{
                {"1:BR_TAGO_GGB233000265", "4403"},
                {"1:BR_TAGO_GGB227000035", "9202"},
                {"1:BR_TAGO_GGB233000333", "8502"},
                {"1:BR_TAGO_GGB234000995", "M4403"}}) {
            SubPathResult sub = firstSubPath(coloredLeg(RoutingMode.BUS, c[0], c[1], "53B332"));
            assertThat(sub.lanes()).singleElement()
                    .satisfies(lane -> assertThat(lane.routeDisplayColor()).as(c[1]).isEqualTo("#E60012"));
        }
    }

    @Test
    @DisplayName("#391 유형별 색: 좌석=파랑, 일반=초록, 마을(BR_3100_)=노랑, 시외=보라, 표 밖 유형·모르는 노선은 그래프 색")
    void gyeonggiRouteTypeTable() {
        LegacyRouteResponseMapper typed = new LegacyRouteResponseMapper(
                new OtpSeoulIdentityResolver(busStopRepository, busRouteRepository,
                        mock(SubwayStationRepository.class),
                        InMemoryRegionalBusRouteStopStore.prefixTableOnlyLookup()),
                GyeonggiBusRouteTypeColors.of(java.util.Map.of(
                        "200000001", 12, "200000002", 13, "241211013", 30, "200000004", 43, "200000005", 50)));
        String[][] cases = {
                {"1:BR_TAGO_GGB200000001", "#0068B7"},
                {"1:BR_TAGO_GGB200000002_U", "#53B332"},
                {"1:BR_3100_241211013", "#F2B70A"},
                {"1:BR_TAGO_GGB200000004", "#8652A1"},
                {"1:BR_TAGO_GGB200000005", "#ABCDEF"},
                {"1:BR_TAGO_GGB299999999", "#ABCDEF"}};
        for (String[] c : cases) {
            SubPathResult sub = firstSubPath(typed, coloredLeg(RoutingMode.BUS, c[0], "x", "ABCDEF"));
            assertThat(sub.lanes()).singleElement()
                    .satisfies(lane -> assertThat(lane.routeDisplayColor()).as(c[0]).isEqualTo(c[1]));
        }
    }
}
