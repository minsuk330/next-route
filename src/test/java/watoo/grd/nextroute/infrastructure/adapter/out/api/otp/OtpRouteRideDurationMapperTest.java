package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import watoo.grd.nextroute.application.route.routing.RideDurationResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** OTP routeRideDuration JSON → RideDurationResult 순수 매핑 계약(delta / 미매칭 / 방향 / 시각 결측). */
class OtpRouteRideDurationMapperTest {

    private static final String ROUTE = "1:BR_TAGO_SEB100100022";
    private static final String BOARD = "1:BS_TAGO_SEB101000041";
    private static final String ALIGHT = "1:BS_TAGO_SEB101000059";

    private final ObjectMapper om = new ObjectMapper();
    private final OtpRouteRideDurationMapper mapper = new OtpRouteRideDurationMapper();

    private JsonNode fixture() throws IOException {
        return om.readTree(new ClassPathResource("otp/routeRideDuration-success.json")
                .getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("매칭 노선 trip 에서 alight.scheduledArrival − board.scheduledDeparture = 소요초")
    void computesScheduledDelta() throws IOException {
        RideDurationResult result = mapper.map(fixture(), ROUTE, BOARD, ALIGHT);

        assertThat(result.status()).isEqualTo(RideDurationResult.Status.OK);
        // board 041 출발 36000, alight 059 도착 36600 → 600초
        assertThat(result.seconds()).isEqualTo(600);
    }

    @Test
    @DisplayName("이 정류장을 지나는 pattern 중 그 route 가 없으면 NOT_FOUND(정상 미매칭)")
    void unmatchedRouteIsNotFound() throws IOException {
        RideDurationResult result = mapper.map(fixture(), "1:BR_TAGO_NONEXISTENT", BOARD, ALIGHT);

        assertThat(result.status()).isEqualTo(RideDurationResult.Status.NOT_FOUND);
        assertThat(result.seconds()).isNull();
    }

    @Test
    @DisplayName("trip 순서상 alight 가 board 보다 앞이면(반대 방향 pattern) NOT_FOUND")
    void reverseDirectionIsNotFound() throws IOException {
        // 매칭 route 이지만 정차 순서가 alight → board (하행) 인 유일 pattern
        String json = """
                {"data":{"stop":{"gtfsId":"%s","stoptimesForPatterns":[
                  {"pattern":{"route":{"gtfsId":"%s"}},
                   "stoptimes":[{"trip":{"gtfsId":"t","stoptimes":[
                     {"stop":{"gtfsId":"%s"},"scheduledArrival":36600,"scheduledDeparture":36620},
                     {"stop":{"gtfsId":"%s"},"scheduledArrival":37200,"scheduledDeparture":37200}
                   ]}}]}
                ]}}}""".formatted(BOARD, ROUTE, ALIGHT, BOARD);

        RideDurationResult result = mapper.map(om.readTree(json), ROUTE, BOARD, ALIGHT);

        assertThat(result.status()).isEqualTo(RideDurationResult.Status.NOT_FOUND);
    }

    @Test
    @DisplayName("board 또는 alight 시각이 null 이면 산출 불가 → NOT_FOUND")
    void missingScheduledTimeIsNotFound() throws IOException {
        String json = """
                {"data":{"stop":{"gtfsId":"%s","stoptimesForPatterns":[
                  {"pattern":{"route":{"gtfsId":"%s"}},
                   "stoptimes":[{"trip":{"gtfsId":"t","stoptimes":[
                     {"stop":{"gtfsId":"%s"},"scheduledArrival":null,"scheduledDeparture":null},
                     {"stop":{"gtfsId":"%s"},"scheduledArrival":36600,"scheduledDeparture":36620}
                   ]}}]}
                ]}}}""".formatted(BOARD, ROUTE, BOARD, ALIGHT);

        assertThat(mapper.map(om.readTree(json), ROUTE, BOARD, ALIGHT).status())
                .isEqualTo(RideDurationResult.Status.NOT_FOUND);
    }

    @Test
    @DisplayName("stop 이 없으면(모르는 id) NOT_FOUND")
    void unknownStopIsNotFound() throws IOException {
        assertThat(mapper.map(om.readTree("{\"data\":{\"stop\":null}}"), ROUTE, BOARD, ALIGHT).status())
                .isEqualTo(RideDurationResult.Status.NOT_FOUND);
    }
}
