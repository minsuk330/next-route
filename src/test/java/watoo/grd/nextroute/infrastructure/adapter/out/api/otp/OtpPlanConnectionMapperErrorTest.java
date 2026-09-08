package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import watoo.grd.nextroute.application.route.routing.RoutingFailure;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.RoutingOutcome;
import watoo.grd.nextroute.application.route.routing.RoutingResult;

import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** routingError 분류 변형 + boardAt/alightAt provider 계약 고정. */
class OtpPlanConnectionMapperErrorTest {

    private final ObjectMapper om = new ObjectMapper();
    private final OtpPlanConnectionMapper mapper = new OtpPlanConnectionMapper();
    private final Instant when = Instant.parse("2026-09-07T23:00:00Z");
    private final OtpPlanConnectionMapper.OtpVersions versions =
            new OtpPlanConnectionMapper.OtpVersions("s", "202503", "g");

    private RoutingOutcome mapError(String code) throws IOException {
        String json = "{\"data\":{\"planConnection\":{\"edges\":[],\"routingErrors\":[{\"code\":\""
                + code + "\",\"description\":\"d\"}]}}}";
        return mapper.map(om.readTree(json), when, versions);
    }

    @Test
    @DisplayName("routingError 분류: 창밖=INPUT_ERROR, 무경로류=NO_ROUTE, 미상=ENGINE_ERROR")
    void classification() throws IOException {
        assertThat(((RoutingFailure) mapError("OUTSIDE_SERVICE_PERIOD")).kind())
                .isEqualTo(RoutingFailure.Kind.INPUT_ERROR);
        assertThat(((RoutingFailure) mapError("NO_TRANSIT_CONNECTION")).kind())
                .isEqualTo(RoutingFailure.Kind.NO_ROUTE);
        assertThat(((RoutingFailure) mapError("NO_STOPS_IN_RANGE")).kind())
                .isEqualTo(RoutingFailure.Kind.NO_ROUTE);
        assertThat(((RoutingFailure) mapError("SOMETHING_NEW")).kind())
                .isEqualTo(RoutingFailure.Kind.ENGINE_ERROR);
    }

    @Test
    @DisplayName("edges 없고 routingErrors 도 없으면 범위 안 무경로(빈 성공)")
    void emptyNoError() throws IOException {
        RoutingOutcome outcome = mapper.map(
                om.readTree("{\"data\":{\"planConnection\":{\"edges\":[],\"routingErrors\":[]}}}"), when, versions);
        assertThat(outcome.success()).isTrue();
        assertThat(((RoutingResult) outcome).itineraries()).isEmpty();
    }

    @Test
    @DisplayName("boardAt/alightAt 계약: OTP 는 모든 leg(도보 포함)에 절대시각을 채운다")
    void boardAtContract() throws IOException {
        JsonNode root = om.readTree(new ClassPathResource("otp/planconnection-success.json").getContentAsByteArray());
        RoutingResult result = (RoutingResult) mapper.map(root, when, versions);
        RoutingLeg walk = result.itineraries().get(0).legs().get(0);
        assertThat(walk.mode()).isEqualTo(RoutingMode.WALK);
        assertThat(walk.boardAt()).isNotNull();     // OTP 는 도보 leg 시작/끝 시각도 준다(계약: provider 의존)
        assertThat(walk.alightAt()).isNotNull();
    }
}
