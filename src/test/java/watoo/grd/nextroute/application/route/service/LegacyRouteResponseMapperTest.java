package watoo.grd.nextroute.application.route.service;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import watoo.grd.nextroute.domain.bus.repository.BusRouteRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * B2/B3 계약: polyline 은 대중교통 {@code SHAPE_OK} 일 때만, 도보는 항상(OTP 실경로) 채운다.
 * 그 외(STREET_FALLBACK/미커버 지하철)는 null(가짜 직선 금지) + 2점 미만은 null(B1 fail-closed 연동).
 */
class LegacyRouteResponseMapperTest {

    // otp/planconnection-success.json 첫 BUS leg 형상(16pts, 롯데백화점 37.5645,126.982 → 고속터미널 37.5058,127.004)
    private static final String BUS_SHAPE =
            "cygdFod`fWfTfE??nPoK??rrB_X??pK???ls@_X??r]gE??jgBwj@??nKg^";
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

    private final LegacyRouteResponseMapper mapper = new LegacyRouteResponseMapper(
            new OtpSeoulIdentityResolver(mock(BusStopRepository.class), mock(BusRouteRepository.class),
                    mock(SubwayStationRepository.class)));

    private RoutingLeg transitLeg(RoutingMode mode, GeometryQuality quality, String points) {
        LegGeometry geometry = new LegGeometry(
                quality == GeometryQuality.SHAPE_OK ? GeometrySource.GTFS_SHAPE : GeometrySource.OTP_STREET,
                quality, "test fixture", "g1", points, points);
        List<StopOccurrence> stops = List.of(
                new StopOccurrence("1:S1", null, null, 0, 126.982, 37.5645, "from"),
                new StopOccurrence("1:S2", null, null, 1, 127.004, 37.5058, "to"));
        return new RoutingLeg(mode, null, "1", "1:A1", "1:R1", "143",
                "1:T1", null, null, null, 600, stops, List.of(),
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
}
