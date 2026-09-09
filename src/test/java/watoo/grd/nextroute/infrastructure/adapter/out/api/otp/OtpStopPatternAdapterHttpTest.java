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
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.StopPatternResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** OtpStopPatternAdapter 의 HTTP 계층: 상태코드/GraphQL errors/예산 → StopPatternResult 상태 분류. */
class OtpStopPatternAdapterHttpTest {

    private static final String STOP_ID = "1:BS_TAGO_SEB101000041";

    private MockWebServer server;
    private OtpStopPatternAdapter adapter;
    private final Instant now = Instant.parse("2026-09-08T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        OtpRoutingProperties props = new OtpRoutingProperties();
        props.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        adapter = new OtpStopPatternAdapter(RestClient.builder(), new ObjectMapper(),
                new OtpStopPatternMapper(), props, clock);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private StopPatternResult call() {
        return adapter.patternsThroughStop(STOP_ID, RoutingDeadline.of(clock, 3000));
    }

    @Test
    @DisplayName("200 정상 → OK + pattern 파싱")
    void ok() throws IOException {
        String body = new ClassPathResource("otp/stopPatterns-success.json")
                .getContentAsString(StandardCharsets.UTF_8);
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(body));

        StopPatternResult result = call();

        assertThat(result.status()).isEqualTo(StopPatternResult.Status.OK);
        assertThat(result.patterns()).hasSize(2);
        assertThat(result.patterns().get(0).routeGtfsId()).isEqualTo("1:BR_TAGO_SEB100100022");
    }

    @Test
    @DisplayName("200 이지만 stop=null(모르는 정류장) → OK + 빈 목록(정상 0건)")
    void unknownStopIsOkButEmpty() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":{\"stop\":null}}"));

        StopPatternResult result = call();

        assertThat(result.status()).isEqualTo(StopPatternResult.Status.OK);
        assertThat(result.patterns()).isEmpty();
    }

    @Test
    @DisplayName("GraphQL errors → ERROR(정상 0건과 구분)")
    void graphqlErrorsAreError() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"errors\":[{\"message\":\"boom\"}],\"data\":null}"));

        assertThat(call().status()).isEqualTo(StopPatternResult.Status.ERROR);
    }

    @Test
    @DisplayName("5xx → ERROR")
    void serverErrorIsError() {
        server.enqueue(new MockResponse().setResponseCode(503));

        StopPatternResult result = call();
        assertThat(result.status()).isEqualTo(StopPatternResult.Status.ERROR);
        assertThat(result.patterns()).isEmpty();
    }

    @Test
    @DisplayName("예산 0 → 호출 없이 TIMEOUT")
    void noBudgetIsTimeout() {
        StopPatternResult result = adapter.patternsThroughStop(STOP_ID, RoutingDeadline.of(clock, 0));
        assertThat(result.status()).isEqualTo(StopPatternResult.Status.TIMEOUT);
    }
}
