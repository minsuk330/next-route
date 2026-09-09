package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.route.config.OtpRoutingProperties;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.StopPatternPort;
import watoo.grd.nextroute.application.route.routing.StopPatternResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * OTP 2.9.0 {@code stop(id).patterns} 어댑터(national-otp P2-F PR4b). {@link OtpRoutingAdapter} 와
 * 같은 HTTP/오류 분류 패턴 — read timeout 은 {@link RoutingDeadline} 남은 예산, 실패는 예외가 아니라
 * {@link StopPatternResult} 상태로 반환한다. 파싱은 {@link OtpStopPatternMapper}(순수).
 *
 * <p><b>미배선</b>: 이 PR 은 어댑터만 둔다. 소비(재탐색 route-option source)는 PR-4.
 */
@Slf4j
@Component
public class OtpStopPatternAdapter implements StopPatternPort {

    private static final String GRAPHQL_PATH = "/otp/routers/default/index/graphql";

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final OtpStopPatternMapper mapper;
    private final OtpRoutingProperties properties;
    private final Clock clock;
    private final String query;
    private final HttpClient httpClient;

    public OtpStopPatternAdapter(RestClient.Builder restClientBuilder, ObjectMapper objectMapper,
                                 OtpStopPatternMapper mapper, OtpRoutingProperties properties,
                                 Clock clock) {
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
        this.query = loadQuery();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
    }

    @Override
    public StopPatternResult patternsThroughStop(String stopGtfsId, RoutingDeadline deadline) {
        long budget = deadline.remainingMillis(clock);
        if (budget <= 0) {
            return StopPatternResult.timeout();
        }
        String response;
        try {
            response = sendOnce(requestBody(stopGtfsId), budget);
        } catch (OtpCallException e) {
            return e.kind == OtpCallException.Kind.TIMEOUT
                    ? StopPatternResult.timeout() : StopPatternResult.error();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (IOException e) {
            return StopPatternResult.error();
        }
        JsonNode errors = root.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            log.debug("[OtpStopPattern] GraphQL errors stop={}: {}", stopGtfsId,
                    errors.get(0).path("message").asText("error"));
            return StopPatternResult.error();
        }
        return StopPatternResult.ok(mapper.map(root));
    }

    private String requestBody(String stopGtfsId) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("query", query, "variables", Map.of("id", stopGtfsId)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String sendOnce(String body, long budgetMs) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
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
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            OtpCallException.Kind kind = msg.toLowerCase().contains("timeout")
                    ? OtpCallException.Kind.TIMEOUT : OtpCallException.Kind.ERROR;
            throw new OtpCallException(kind, "OTP stop-pattern 호출 실패: " + msg);
        }
        int status = response.getStatusCode().value();
        if (status >= 400) {
            throw new OtpCallException(OtpCallException.Kind.ERROR, "OTP HTTP " + status);
        }
        String responseBody = response.getBody();
        if (responseBody == null || responseBody.isBlank()) {
            throw new OtpCallException(OtpCallException.Kind.ERROR, "OTP 빈 응답");
        }
        return responseBody;
    }

    private String loadQuery() {
        try {
            return new ClassPathResource("otp/stopPatterns.graphql")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("stopPatterns.graphql 로드 실패", e);
        }
    }

    /** 내부 전용 — HTTP 단계 실패를 StopPatternResult 상태로 옮기기 위한 캐리어. */
    private static final class OtpCallException extends RuntimeException {
        enum Kind { TIMEOUT, ERROR }

        private final Kind kind;

        OtpCallException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }
    }
}
