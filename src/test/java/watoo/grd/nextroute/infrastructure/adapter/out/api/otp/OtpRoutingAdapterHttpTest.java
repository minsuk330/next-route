package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** OtpRoutingAdapter 의 실제 HTTP 계층: 상태코드/GraphQL errors → RoutingFailure.Kind 분류. */
class OtpRoutingAdapterHttpTest {

    private MockWebServer server;
    private OtpRoutingAdapter adapter;
    private OtpRoutingProperties props;
    private final Instant now = Instant.parse("2026-09-08T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        props = new OtpRoutingProperties();
        props.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        adapter = new OtpRoutingAdapter(RestClient.builder(), new ObjectMapper(),
                new OtpPlanConnectionMapper(), props, clock);
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
    @DisplayName("요청: 후보 20개·탐색 창 30분·버스 가중치 1.3, 나머지 수단은 전부 그대로 탐색")
    void requestCarriesCandidatePoolWindowAndBusReluctance() throws Exception {
        enqueueSuccess();

        call();

        JsonNode sent = new ObjectMapper().readTree(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        assertThat(sent.path("query").asText()).contains("generalizedCost", "$searchWindow", "$modes");
        JsonNode vars = sent.path("variables");
        assertThat(vars.path("first").asInt()).isEqualTo(20);
        assertThat(vars.path("searchWindow").asText()).isEqualTo("PT30M");
        JsonNode modes = vars.path("modes").path("transit").path("transit");
        // 목록을 주면 그 수단만 탐색한다 → OTP TransitMode 전부를 나열해야 탐색 범위가 그대로다.
        assertThat(modes).hasSize(15);
        java.util.Map<String, JsonNode> byMode = new java.util.HashMap<>();
        modes.forEach(m -> byMode.put(m.path("mode").asText(), m));
        assertThat(byMode).containsKeys("BUS", "SUBWAY", "RAIL", "TRAM", "MONORAIL", "FERRY", "COACH");
        assertThat(byMode.get("BUS").path("cost").path("reluctance").asDouble()).isEqualTo(1.3);
        assertThat(byMode.get("SUBWAY").has("cost")).isFalse();
    }

    @Test
    @DisplayName("가중치 1.0·창 미지정이면 그 변수를 보내지 않는다 — OTP 기본 탐색과 같다")
    void neutralSettingsOmitVariables() throws Exception {
        props.setBusReluctance(1.0);
        props.setSearchWindow(null);
        enqueueSuccess();

        call();

        JsonNode vars = new ObjectMapper().readTree(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8())
                .path("variables");
        assertThat(vars.has("modes")).isFalse();
        assertThat(vars.has("searchWindow")).isFalse();
    }

    private void enqueueSuccess() throws IOException {
        String body = new ClassPathResource("otp/planconnection-success.json").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(body));
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

    @Test
    @DisplayName("헤더 뒤 본문이 예산을 넘기면 TIMEOUT — 예외 원문을 메시지에 싣지 않는다")
    void bodyReadTimeoutIsTimeout() {
        // 2026-10-05 prod 재현: Content-Type 없이 헤더만 먼저 오고 본문이 예산을 넘겼다.
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"data\":{}}")
                .setBodyDelay(5, TimeUnit.SECONDS));

        RoutingFailure failure = (RoutingFailure) call();

        assertThat(failure.kind()).isEqualTo(RoutingFailure.Kind.TIMEOUT);
        assertThat(failure.message()).isEqualTo("OTP 응답 시간 초과")
                .doesNotContain("extracting");
    }

    @Test
    @DisplayName("응답 헤더 전에 예산을 넘겨도 TIMEOUT")
    void headerReadTimeoutIsTimeout() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{}")
                .setHeadersDelay(5, TimeUnit.SECONDS));

        assertThat(((RoutingFailure) call()).kind()).isEqualTo(RoutingFailure.Kind.TIMEOUT);
    }
}
