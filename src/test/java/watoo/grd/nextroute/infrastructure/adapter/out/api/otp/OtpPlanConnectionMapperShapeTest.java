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

import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 형상 계약: 대중교통 leg 는 그래프의 pfaedle shapes.txt 형상을 그대로 SHAPE_OK 로 싣는다.
 * trip 별 coverage-index 게이트는 없다.
 *
 * <p>success fixture 는 버스 2개 leg(idx 1, idx 3)를 담는다.
 */
class OtpPlanConnectionMapperShapeTest {

    private final ObjectMapper om = new ObjectMapper();
    private final Instant departureAt = Instant.parse("2026-09-07T23:00:00Z");
    private final OtpPlanConnectionMapper.OtpVersions versions =
            new OtpPlanConnectionMapper.OtpVersions("schema-v1", "202503", "g-abc");
    private final OtpPlanConnectionMapper mapper = new OtpPlanConnectionMapper();

    private RoutingItinerary firstItinerary(String fixture) throws IOException {
        JsonNode root = om.readTree(new ClassPathResource(fixture).getContentAsByteArray());
        RoutingResult result = (RoutingResult) mapper.map(root, departureAt, versions);
        return result.itineraries().get(0);
    }

    @Test
    @DisplayName("버스 leg → GTFS_SHAPE/SHAPE_OK, points 와 그래프 버전 보존")
    void busIsShapeOk() throws IOException {
        RoutingLeg bus = firstItinerary("otp/planconnection-success.json").legs().get(1);

        assertThat(bus.mode()).isEqualTo(RoutingMode.BUS);
        assertThat(bus.geometry().source()).isEqualTo(GeometrySource.GTFS_SHAPE);
        assertThat(bus.geometry().quality()).isEqualTo(GeometryQuality.SHAPE_OK);
        assertThat(bus.geometry().version()).isEqualTo("g-abc");
        assertThat(bus.geometry().displayPoints()).isNotBlank();
        assertThat(bus.geometry().progressPoints()).isNotBlank();
    }

    @Test
    @DisplayName("모든 대중교통 leg 가 SHAPE_OK → GEOMETRY_STREET_FALLBACK 로 막히지 않는다")
    void transitIsNotBlockedByGeometry() throws IOException {
        RoutingItinerary it = firstItinerary("otp/planconnection-success.json");

        assertThat(it.legs()).filteredOn(RoutingLeg::isTransit)
                .allMatch(l -> l.geometry().quality() == GeometryQuality.SHAPE_OK);
        assertThat(it.navigationBlockedReasons()).doesNotContain("GEOMETRY_STREET_FALLBACK");
    }

    @Test
    @DisplayName("지하철 leg 도 shapes.txt 형상 → SHAPE_OK")
    void subwayIsShapeOk() throws IOException {
        RoutingLeg subway = firstItinerary("otp/planconnection-subway.json").legs().get(0);

        assertThat(subway.mode()).isEqualTo(RoutingMode.SUBWAY);
        assertThat(subway.geometry().quality()).isEqualTo(GeometryQuality.SHAPE_OK);
    }

    @Test
    @DisplayName("도보 leg 는 OTP 도보 경로 그대로(STREET_FALLBACK 표기)")
    void walkStaysStreet() throws IOException {
        RoutingLeg walk = firstItinerary("otp/planconnection-success.json").legs().get(0);

        assertThat(walk.mode()).isEqualTo(RoutingMode.WALK);
        assertThat(walk.geometry().source()).isEqualTo(GeometrySource.OTP_STREET);
        assertThat(walk.geometry().quality()).isEqualTo(GeometryQuality.STREET_FALLBACK);
    }
}
