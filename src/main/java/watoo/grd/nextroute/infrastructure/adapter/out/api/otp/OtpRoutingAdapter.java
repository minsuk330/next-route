package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.route.config.OtpRoutingProperties;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingFailure;
import watoo.grd.nextroute.application.route.routing.RoutingOutcome;
import watoo.grd.nextroute.application.route.routing.RoutingPort;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingQuery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OTP 2.9.0 planConnection 어댑터. 선택된 provider 가 OTP 일 때만 호출된다(selector 는 PR2b).
 *
 * <p>파싱/매핑은 {@link OtpPlanConnectionMapper}(순수)가 하고, 여기서는 HTTP 와 오류 분류만 한다.
 * read timeout 은 {@link RoutingDeadline} 의 남은 예산으로 요청마다 건다(OdSay 어댑터와 같은 패턴).
 * 실패는 예외가 아니라 {@link RoutingFailure} 로 반환한다.
 *
 * <p><b>{@link SimpleClientHttpRequestFactory}(HttpURLConnection 기반) 사용, {@code JdkClientHttpRequestFactory}
 * 아님</b> — 2026-09-17 전환 실측: OTP 서버가 POST body 를 다 못 받고 EOFException 을 던지며 정확히
 * 예산(otpMs) 만큼 걸려 취소됨(otp CPU 0%, 순수 대기). JdkClientHttpRequestFactory 는 body 를
 * {@code HttpRequest.BodyPublishers.fromPublisher}(Flow.Publisher, 비동기) 로 보내는데, 이 경로에서
 * body flush 가 멈추는 현상 재현(격리 테스트 — OTP 직접/도커내부망 직접 호출은 전부 1~1.5s 로 정상,
 * 이 앱의 JdkClientHttpRequestFactory 경유만 매번 3.5s 벽에서 취소). ODSAY 어댑터가 같은
 * JdkClientHttpRequestFactory 를 오래 문제없이 써온 건 GET(body 없음)이라 이 경로를 안 탔기 때문 —
 * "OTP 가 느리다"가 아니라 "이 앱에서 POST body 를 보내는 유일한 hot-path 어댑터"라 여기서만 드러남.
 */
@Slf4j
@Component
public class OtpRoutingAdapter implements RoutingPort {

    private static final String GRAPHQL_PATH = "/otp/routers/default/index/graphql";
    private static final ZoneId FEED_ZONE = ZoneId.of("Asia/Seoul");

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final OtpPlanConnectionMapper mapper;
    private final OtpRoutingProperties properties;
    private final Clock clock;
    private final String query;

    public OtpRoutingAdapter(RestClient.Builder restClientBuilder, ObjectMapper objectMapper,
                             OtpPlanConnectionMapper mapper, OtpRoutingProperties properties,
                             Clock clock) {
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
        this.query = loadQuery();
    }

    @Override
    public RoutingProvider provider() {
        return RoutingProvider.OTP;
    }

    @Override
    public RoutingOutcome search(RoutingQuery routingQuery, RoutingDeadline deadline) {
        long budget = deadline.remainingMillis(clock);
        if (budget <= 0) {
            return RoutingFailure.of(RoutingFailure.Kind.TIMEOUT, "NO_BUDGET",
                    "OTP 호출 전에 시간 예산이 소진됨");
        }
        String body = requestBody(routingQuery);
        String response;
        try {
            response = sendOnce(body, budget);
        } catch (OtpCallException e) {
            return RoutingFailure.of(e.kind, e.code, e.getMessage());
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (IOException e) {
            return RoutingFailure.of(RoutingFailure.Kind.ENGINE_ERROR, "BAD_JSON",
                    "OTP 응답 JSON 파싱 실패");
        }
        // HTTP 200 이어도 GraphQL 최상위 errors 가 있으면 엔진 오류로 분류(부분 응답 방지).
        JsonNode errors = root.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            String msg = errors.get(0).path("message").asText("GraphQL error");
            return RoutingFailure.of(RoutingFailure.Kind.ENGINE_ERROR, "GRAPHQL_ERROR", msg);
        }

        OtpPlanConnectionMapper.OtpVersions versions = new OtpPlanConnectionMapper.OtpVersions(
                properties.getSchemaVersion(), properties.getNetworkDataVersion(),
                properties.getGraphVersion());
        // 실시간 coverage 배선은 #1 검색 응답이 소비하지 않는다(정적 드롭인). 주행중 재탐색(#2 P2-F)이
        // 소비지점에서 적용한다 → 여기서는 중립 결과를 그대로 반환. OtpLegCoverageResolver 는 #2 용으로 존치.
        return mapper.map(root, routingQuery.departureAt(), versions);
    }

    private String requestBody(RoutingQuery q) {
        Map<String, Object> coordO = Map.of("latitude", q.originLat(), "longitude", q.originLon());
        Map<String, Object> coordD = Map.of("latitude", q.destLat(), "longitude", q.destLon());
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("origin", Map.of("location", Map.of("coordinate", coordO)));
        vars.put("destination", Map.of("location", Map.of("coordinate", coordD)));
        // offset 포함 확정 departureAt 을 feed 시간대로 표현(OTP OffsetDateTime).
        vars.put("when", q.departureAt().atZone(FEED_ZONE).toOffsetDateTime().toString());
        vars.put("first", properties.getFirst());
        try {
            return objectMapper.writeValueAsString(Map.of("query", query, "variables", vars));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String sendOnce(String body, long budgetMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(budgetMs));
        RestClient client = restClientBuilder.clone().requestFactory(factory).build();
        ResponseEntity<String> response;
        try {
            response = client.post()
                    .uri(properties.getBaseUrl() + GRAPHQL_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> true, (req, res) -> { })  // 기본 예외 억제 — 아래서 분류
                    .toEntity(String.class);
        } catch (Exception e) {
            // 연결/read timeout 등 I/O — 재시도 가능 성격이나 예산 안에서 끝나야 하므로 TIMEOUT/UPSTREAM.
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            RoutingFailure.Kind kind = msg.toLowerCase().contains("timeout")
                    ? RoutingFailure.Kind.TIMEOUT : RoutingFailure.Kind.ENGINE_ERROR;
            throw new OtpCallException(kind, "IO", "OTP 호출 실패: " + msg);
        }
        int status = response.getStatusCode().value();
        if (status >= 500) {
            throw new OtpCallException(RoutingFailure.Kind.ENGINE_ERROR, "HTTP_" + status, "OTP 5xx");
        }
        if (status >= 400) {
            throw new OtpCallException(RoutingFailure.Kind.ENGINE_ERROR, "HTTP_" + status, "OTP 4xx");
        }
        String responseBody = response.getBody();
        if (responseBody == null || responseBody.isBlank()) {
            throw new OtpCallException(RoutingFailure.Kind.ENGINE_ERROR, "EMPTY", "OTP 빈 응답");
        }
        return responseBody;
    }

    private String loadQuery() {
        try {
            return new ClassPathResource("otp/planConnection.graphql")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("planConnection.graphql 로드 실패", e);
        }
    }

    /** 내부 전용 — HTTP 단계 실패를 RoutingFailure 로 옮기기 위한 캐리어. */
    private static final class OtpCallException extends RuntimeException {
        private final RoutingFailure.Kind kind;
        private final String code;

        OtpCallException(RoutingFailure.Kind kind, String code, String message) {
            super(message);
            this.kind = kind;
            this.code = code;
        }
    }
}
