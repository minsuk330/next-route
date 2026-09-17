package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import watoo.grd.nextroute.application.route.routing.GeometryQuality;
import watoo.grd.nextroute.application.route.routing.GeometrySource;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.route.routing.ShapeCoverageIndex;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 형상 마킹 계약: pfaedle coverage-index 로만 SHAPE_OK 를 판정한다(PR-B1).
 *
 * <p>success fixture 는 버스 2개 leg(idx 1 = trip …022_Ord039, idx 3 = trip …096_Ord018)를 담는다.
 */
class OtpPlanConnectionMapperShapeTest {

    private final ObjectMapper om = new ObjectMapper();
    private final Instant departureAt = Instant.parse("2026-09-07T23:00:00Z");
    private final OtpPlanConnectionMapper.OtpVersions versions =
            new OtpPlanConnectionMapper.OtpVersions("schema-v1", "202503", "g-abc");

    /** graphVersion=g-abc, 버스 커버, …096 만 degraded. */
    private final ShapeCoverageIndex index = new ShapeCoverageIndex(
            "g-abc", Set.of("bus"), Set.of("BR_TAGO_SEB100100096_Ord018"));

    private JsonNode load(String path) throws IOException {
        return om.readTree(new ClassPathResource(path).getContentAsByteArray());
    }

    private RoutingItinerary firstItinerary(OtpPlanConnectionMapper mapper, String fixture,
                                            OtpPlanConnectionMapper.OtpVersions v) throws IOException {
        RoutingResult result = (RoutingResult) mapper.map(load(fixture), departureAt, v);
        return result.itineraries().get(0);
    }

    @Test
    @DisplayName("① index-covered 버스 trip → SHAPE_OK + GTFS_SHAPE, points 보존")
    void coveredBusIsShapeOk() throws IOException {
        OtpPlanConnectionMapper mapper = new OtpPlanConnectionMapper(index, true);
        RoutingLeg bus = firstItinerary(mapper, "otp/planconnection-success.json", versions).legs().get(1);

        assertThat(bus.mode()).isEqualTo(RoutingMode.BUS);
        assertThat(bus.tripId()).isEqualTo("1:BR_TAGO_SEB100100022_Ord039");
        assertThat(bus.geometry().source()).isEqualTo(GeometrySource.GTFS_SHAPE);
        assertThat(bus.geometry().quality()).isEqualTo(GeometryQuality.SHAPE_OK);
        assertThat(bus.geometry().version()).isEqualTo("g-abc");
        assertThat(bus.geometry().displayPoints()).isNotBlank();
        assertThat(bus.geometry().progressPoints()).isNotBlank();
    }

    @Test
    @DisplayName("② degraded trip → STREET_FALLBACK, itinerary 는 GEOMETRY_STREET_FALLBACK 로 네비 차단")
    void degradedBusFallsBack() throws IOException {
        OtpPlanConnectionMapper mapper = new OtpPlanConnectionMapper(index, true);
        RoutingItinerary it = firstItinerary(mapper, "otp/planconnection-success.json", versions);

        RoutingLeg degraded = it.legs().get(3);
        assertThat(degraded.tripId()).isEqualTo("1:BR_TAGO_SEB100100096_Ord018");
        assertThat(degraded.geometry().quality()).isEqualTo(GeometryQuality.STREET_FALLBACK);
        // 하나라도 fallback 이면 여정 네비 차단 유지.
        assertThat(it.navigationBlockedReasons()).contains("GEOMETRY_STREET_FALLBACK");
    }

    @Test
    @DisplayName("② b: 모든 버스 leg SHAPE_OK → GEOMETRY_STREET_FALLBACK 미발행")
    void allShapeOkNotBlocked() throws IOException {
        ShapeCoverageIndex allOk = new ShapeCoverageIndex("g-abc", Set.of("bus"), Set.of());
        OtpPlanConnectionMapper mapper = new OtpPlanConnectionMapper(allOk, true);
        RoutingItinerary it = firstItinerary(mapper, "otp/planconnection-success.json", versions);

        assertThat(it.legs()).filteredOn(RoutingLeg::isTransit)
                .allMatch(l -> l.geometry().quality() == GeometryQuality.SHAPE_OK);
        assertThat(it.navigationBlockedReasons()).doesNotContain("GEOMETRY_STREET_FALLBACK");
    }

    @Test
    @DisplayName("③ graphVersion mismatch → 전량 fallback(오마킹 0)")
    void graphVersionMismatchFallsBack() throws IOException {
        OtpPlanConnectionMapper mapper = new OtpPlanConnectionMapper(index, true);
        var mismatch = new OtpPlanConnectionMapper.OtpVersions("schema-v1", "202503", "g-DIFFERENT");
        RoutingItinerary it = firstItinerary(mapper, "otp/planconnection-success.json", mismatch);

        assertThat(it.legs()).filteredOn(RoutingLeg::isTransit)
                .allMatch(l -> l.geometry().quality() == GeometryQuality.STREET_FALLBACK);
    }

    @Test
    @DisplayName("④ 비버스(지하철) leg → coveredModes=[bus] 라 fallback")
    void subwayNotCovered() throws IOException {
        ShapeCoverageIndex busOnly = new ShapeCoverageIndex("g-abc", Set.of("bus"), Set.of());
        OtpPlanConnectionMapper mapper = new OtpPlanConnectionMapper(busOnly, true);
        RoutingLeg subway = firstItinerary(mapper, "otp/planconnection-subway.json", versions).legs().get(0);

        assertThat(subway.mode()).isEqualTo(RoutingMode.SUBWAY);
        assertThat(subway.geometry().quality()).isEqualTo(GeometryQuality.STREET_FALLBACK);
    }

    @Test
    @DisplayName("⑤ shapesEnabled=false → 인덱스 커버해도 전량 fallback")
    void flagOffFallsBack() throws IOException {
        OtpPlanConnectionMapper mapper = new OtpPlanConnectionMapper(index, false);
        RoutingLeg bus = firstItinerary(mapper, "otp/planconnection-success.json", versions).legs().get(1);

        assertThat(bus.geometry().quality()).isEqualTo(GeometryQuality.STREET_FALLBACK);
    }
}
