package watoo.grd.nextroute.application.route.service;

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
import watoo.grd.nextroute.application.route.routing.StopOccurrence;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.repository.BusRouteRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
                    mock(SubwayStationRepository.class)), busRouteRepository);

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
        RoutingItinerary itinerary = new RoutingItinerary(NOW, 600, 0, 0, 0, false, null, null, List.of(leg));
        RoutingResult result = new RoutingResult(RoutingProvider.OTP, "v2", "net1", "g1", null, null,
                NOW, List.of(), List.of(itinerary));
        RouteSearchResult legacy = mapper.toLegacy(result);
        return legacy.paths().get(0).subPaths().get(0);
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

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("subwayLineColors")
    @DisplayName("OTP 지하철 route.shortName을 노선 표시색에 연결한다")
    void otpSubwayRouteShortNameHasDisplayColor(String routeShortName, String expectedColor) {
        SubPathResult sub = firstSubPath(transitLeg(RoutingMode.SUBWAY, GeometryQuality.STREET_FALLBACK,
                BUS_SHAPE, "1:RR_ACC1_" + routeShortName, routeShortName));

        assertThat(sub.lanes()).singleElement().satisfies(lane -> {
            assertThat(lane.name()).isEqualTo(routeShortName);
            assertThat(lane.routeDisplayColor()).isEqualTo(expectedColor);
        });
    }

    static Stream<Arguments> subwayLineColors() {
        return Stream.of(
                Arguments.of("서울1호선", "#004A85"), Arguments.of("서울2호선", "#00A23F"),
                Arguments.of("서울3호선", "#ED6C00"), Arguments.of("서울4호선", "#009BCE"),
                Arguments.of("서울5호선", "#794698"), Arguments.of("서울6호선", "#7C4932"),
                Arguments.of("서울7호선", "#6E7E31"), Arguments.of("서울8호선", "#D11D70"),
                Arguments.of("서울9호선", "#A49D87"), Arguments.of("인천1호선", "#B4C7E7"),
                Arguments.of("인천2호선", "#F4A462"), Arguments.of("공항철도", "#0079AC"),
                Arguments.of("경춘선", "#007A62"), Arguments.of("경의중앙선", "#6AC2B3"),
                Arguments.of("신분당선", "#B81B30"), Arguments.of("수인분당선", "#ECA300"),
                Arguments.of("서해선", "#5EAC41"), Arguments.of("GTX-A", "#AB087D"),
                Arguments.of("김포도시철도", "#957326"), Arguments.of("신림선", "#6789CA"),
                Arguments.of("우이신설경전철", "#BACC50"), Arguments.of("의정부경전철", "#F0831E"),
                Arguments.of("용인경전철", "#44A436")
        );
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

    @ParameterizedTest(name = "routeType={0} → {1}")
    @MethodSource("seoulBusRouteTypeColors")
    @DisplayName("OTP 서울 버스를 DB routeType 표시색에 연결한다")
    void otpBusRouteTypeHasDisplayColor(int routeType, String expectedColor) {
        String routeId = "100100001";
        when(busStopRepository.existsByStopId(routeId)).thenReturn(true);
        when(busRouteRepository.existsByRouteId(routeId)).thenReturn(true);
        when(busRouteRepository.findByRouteId(routeId)).thenReturn(Optional.of(
                BusRoute.builder().routeId(routeId).routeName("143").routeType(routeType).build()));

        SubPathResult sub = firstSubPath(transitLeg(RoutingMode.BUS, GeometryQuality.STREET_FALLBACK,
                BUS_SHAPE, "1:BR_TAGO_SEB" + routeId, "143"));

        assertThat(sub.lanes()).singleElement().satisfies(lane -> {
            assertThat(lane.busLocalBlID()).isEqualTo(routeId);
            assertThat(lane.routeDisplayColor()).isEqualTo(expectedColor);
        });
    }

    static Stream<Arguments> seoulBusRouteTypeColors() {
        return Stream.of(
                Arguments.of(2, "#53b332"), Arguments.of(3, "#0068b7"), Arguments.of(4, "#53b332"),
                Arguments.of(5, "#f2b70a"), Arguments.of(6, "#e60012")
        );
    }

    @Test
    @DisplayName("OTP 비서울 버스 routeType은 표시색을 비운다")
    void otpUnsupportedBusRouteTypeKeepsColorNull() {
        String routeId = "100100001";
        when(busStopRepository.existsByStopId(routeId)).thenReturn(true);
        when(busRouteRepository.existsByRouteId(routeId)).thenReturn(true);
        when(busRouteRepository.findByRouteId(routeId)).thenReturn(Optional.of(
                BusRoute.builder().routeId(routeId).routeName("143").routeType(7).build()));

        SubPathResult sub = firstSubPath(transitLeg(RoutingMode.BUS, GeometryQuality.STREET_FALLBACK,
                BUS_SHAPE, "1:BR_TAGO_SEB" + routeId, "143"));

        assertThat(sub.lanes()).singleElement().extracting(lane -> lane.routeDisplayColor()).isNull();
    }
}
