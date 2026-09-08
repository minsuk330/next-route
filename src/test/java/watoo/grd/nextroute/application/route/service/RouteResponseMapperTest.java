package watoo.grd.nextroute.application.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.dto.v2.RouteSearchResultV2;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.infrastructure.adapter.out.api.otp.OtpPlanConnectionMapper;

import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** OTP fixture → 중립 RoutingResult → legacy/v2 응답 전체 체인 계약. */
class RouteResponseMapperTest {

    private final ObjectMapper om = new ObjectMapper();
    private final OtpPlanConnectionMapper otpMapper = new OtpPlanConnectionMapper();
    private final LegacyRouteResponseMapper legacyMapper = new LegacyRouteResponseMapper();
    private final RouteSearchV2Mapper v2Mapper = new RouteSearchV2Mapper();

    private RoutingResult routingResult() throws IOException {
        var root = om.readTree(new ClassPathResource("otp/planconnection-success.json").getContentAsByteArray());
        var versions = new OtpPlanConnectionMapper.OtpVersions("schema-v1", "202503", "g-abc");
        return (RoutingResult) otpMapper.map(root, Instant.parse("2026-09-07T23:00:00Z"), versions);
    }

    @Test
    @DisplayName("legacy: trafficType/정류장/요금 sentinel(-1). OTP fare 없음 → payment=-1(0 아님)")
    void toLegacy() throws IOException {
        RouteSearchResult legacy = legacyMapper.toLegacy(routingResult());
        assertThat(legacy.paths()).isNotEmpty();
        PathResult path = legacy.paths().get(0);
        // OTP fare 없음 → UNKNOWN → -1 sentinel
        assertThat(path.info().payment()).isEqualTo(LegacyRouteResponseMapper.PAYMENT_UNKNOWN);
        assertThat(path.info().transferCount()).isEqualTo(1);
        assertThat(legacy.busCount()).isGreaterThan(0);
        // 첫 leg WALK(3), 둘째 BUS(2)
        assertThat(path.subPaths().get(0).trafficType()).isEqualTo(3);
        SubPathResult bus = path.subPaths().get(1);
        assertThat(bus.trafficType()).isEqualTo(2);
        assertThat(bus.stations()).isNotEmpty();
        assertThat(bus.stations().get(0).stationID()).startsWith("1:");   // 원천 gtfsId 보존
        // 채우지 못하는 필드는 null(가짜값 금지)
        assertThat(bus.polyline()).isNull();
        assertThat(path.info().mapObj()).isNull();
    }

    @Test
    @DisplayName("v2: provider/graphVersion + nullable 요금(UNKNOWN→null) + coverage/geometryQuality 노출")
    void toV2() throws IOException {
        RouteSearchResultV2 v2 = v2Mapper.toV2(routingResult());
        assertThat(v2.provider()).isEqualTo("OTP");
        assertThat(v2.graphVersion()).isEqualTo("g-abc");
        var path = v2.paths().get(0);
        assertThat(path.fare().availability()).isEqualTo("UNKNOWN");
        assertThat(path.fare().amount()).isNull();       // UNKNOWN → null(0 아님)
        assertThat(path.navigationSupported()).isFalse();
        var bus = path.subPaths().get(1);
        assertThat(bus.mode()).isEqualTo("BUS");
        assertThat(bus.geometryQuality()).isEqualTo("STREET_FALLBACK");
        assertThat(bus.missing()).contains("REALTIME_ID", "DIRECTION");
    }
}
