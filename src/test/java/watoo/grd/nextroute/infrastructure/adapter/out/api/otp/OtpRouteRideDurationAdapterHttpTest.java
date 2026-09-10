package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.route.config.OtpRoutingProperties;
import watoo.grd.nextroute.application.route.routing.RideDurationResult;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** OtpRouteRideDurationAdapter 의 HTTP 계층: 상태코드/GraphQL errors/예산 → RideDurationResult 분류. */
class OtpRouteRideDurationAdapterHttpTest {

    private static final String ROUTE = "1:BR_TAGO_SEB100100022";
    private static final String BOARD = "1:BS_TAGO_SEB101000041";
    private static final String ALIGHT = "1:BS_TAGO_SEB101000059";

    private MockWebServer server;
    private OtpRouteRideDurationAdapter adapter;
    private final Instant now = Instant.parse("2026-09-08T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        OtpRoutingProperties props = new OtpRoutingProperties();
        props.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        adapter = new OtpRouteRideDurationAdapter(RestClient.builder(), new ObjectMapper(),
                new OtpRouteRideDurationMapper(), props, clock);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private RideDurationResult call(String route) {
        return adapter.rideDurationSeconds(route, BOARD, ALIGHT, RoutingDeadline.of(clock, 3000));
    }

    @Test
    @DisplayName("200 정상 → OK + 소요초")
    void ok() throws IOException {
        String body = new ClassPathResource("otp/routeRideDuration-success.json")
                .getContentAsString(StandardCharsets.UTF_8);
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(body));

        RideDurationResult result = call(ROUTE);

        assertThat(result.status()).isEqualTo(RideDurationResult.Status.OK);
        assertThat(result.seconds()).isEqualTo(600);
    }

    @Test
    @DisplayName("200 이지만 그 노선이 이 정류장을 안 지나면 → NOT_FOUND(정상 미매칭, 장애 아님)")
    void unmatchedRouteIsNotFound() throws IOException {
        String body = new ClassPathResource("otp/routeRideDuration-success.json")
                .getContentAsString(StandardCharsets.UTF_8);
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(body));

        assertThat(call("1:BR_TAGO_NONEXISTENT").status())
                .isEqualTo(RideDurationResult.Status.NOT_FOUND);
    }

    @Test
    @DisplayName("GraphQL errors → ERROR(미매칭과 구분)")
    void graphqlErrorsAreError() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"errors\":[{\"message\":\"boom\"}],\"data\":null}"));

        assertThat(call(ROUTE).status()).isEqualTo(RideDurationResult.Status.ERROR);
    }

    @Test
    @DisplayName("5xx → ERROR")
    void serverErrorIsError() {
        server.enqueue(new MockResponse().setResponseCode(503));

        assertThat(call(ROUTE).status()).isEqualTo(RideDurationResult.Status.ERROR);
    }

    @Test
    @DisplayName("예산 0 → 호출 없이 TIMEOUT")
    void noBudgetIsTimeout() {
        RideDurationResult result =
                adapter.rideDurationSeconds(ROUTE, BOARD, ALIGHT, RoutingDeadline.of(clock, 0));
        assertThat(result.status()).isEqualTo(RideDurationResult.Status.TIMEOUT);
    }
}
