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
import watoo.grd.nextroute.application.route.routing.RoutingFailure;
import watoo.grd.nextroute.application.route.routing.RoutingOutcome;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingQuery;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** OtpRoutingAdapter 의 실제 HTTP 계층: 상태코드/GraphQL errors → RoutingFailure.Kind 분류. */
class OtpRoutingAdapterHttpTest {

    private MockWebServer server;
    private OtpRoutingAdapter adapter;
    private final Instant now = Instant.parse("2026-09-08T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        OtpRoutingProperties props = new OtpRoutingProperties();
        props.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        adapter = new OtpRoutingAdapter(RestClient.builder(), new ObjectMapper(),
                new OtpPlanConnectionMapper(), props, clock,
                new watoo.grd.nextroute.application.route.mapping.OtpLegCoverageResolver(
                        org.mockito.Mockito.mock(watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver.class)));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private RoutingOutcome call() {
        RoutingQuery q = new RoutingQuery(126.97, 37.56, "o", 127.02, 37.49, "d",
                now, Set.of(), null, false, "v", RoutingProvider.OTP, null);
        return adapter.search(q, RoutingDeadline.of(clock, 3000));
    }

    @Test
    @DisplayName("200 정상 응답 → 성공")
    void ok() throws IOException {
        String body = new ClassPathResource("otp/planconnection-success.json").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(body));
        assertThat(call().success()).isTrue();
    }

    @Test
    @DisplayName("5xx → ENGINE_ERROR")
    void serverError() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("boom"));
        RoutingOutcome outcome = call();
        assertThat(outcome.success()).isFalse();
        assertThat(((RoutingFailure) outcome).kind()).isEqualTo(RoutingFailure.Kind.ENGINE_ERROR);
    }

    @Test
    @DisplayName("HTTP 200 + GraphQL top-level errors → ENGINE_ERROR(부분응답 방지)")
    void graphqlErrors() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"errors\":[{\"message\":\"boom\"}],\"data\":null}"));
        RoutingOutcome outcome = call();
        assertThat(outcome.success()).isFalse();
        assertThat(((RoutingFailure) outcome).kind()).isEqualTo(RoutingFailure.Kind.ENGINE_ERROR);
        assertThat(((RoutingFailure) outcome).code()).isEqualTo("GRAPHQL_ERROR");
    }
}
