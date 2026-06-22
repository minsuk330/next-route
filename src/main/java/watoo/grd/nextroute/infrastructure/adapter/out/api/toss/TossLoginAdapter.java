package watoo.grd.nextroute.infrastructure.adapter.out.api.toss;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.auth.config.TossLoginProperties;
import watoo.grd.nextroute.application.auth.exception.TossLoginException;
import watoo.grd.nextroute.application.auth.port.out.TossLoginPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class TossLoginAdapter implements TossLoginPort {

    private static final String BASE = "/api-partner/v1/apps-in-toss/user/oauth2";
    private static final String PATH_GENERATE = BASE + "/generate-token";
    private static final String PATH_REFRESH = BASE + "/refresh-token";
    private static final String PATH_ME = BASE + "/login-me";
    private static final String PATH_REMOVE_BY_TOKEN = BASE + "/access/remove-by-access-token";
    private static final String PATH_REMOVE_BY_USER_KEY = BASE + "/access/remove-by-user-key";

    private final RestClient restClient;
    private final TossLoginProperties properties;
    private final TossCryptoService cryptoService;

    public TossLoginAdapter(@Qualifier("tossRestClient") RestClient restClient,
                            TossLoginProperties properties,
                            TossCryptoService cryptoService) {
        this.restClient = restClient;
        this.properties = properties;
        this.cryptoService = cryptoService;
    }

    @Override
    public TossToken generateToken(String authorizationCode, String referrer) {
        JsonNode root = postJson(PATH_GENERATE,
                Map.of("authorizationCode", authorizationCode, "referrer", referrer));
        return toToken(requireSuccess(root, "generate-token"));
    }

    @Override
    public TossToken refreshToken(String refreshToken) {
        JsonNode root = postJson(PATH_REFRESH, Map.of("refreshToken", refreshToken));
        return toToken(requireSuccess(root, "refresh-token"));
    }

    @Override
    public TossUserInfo getUserInfo(String accessToken) {
        JsonNode root = withRetry("login-me", () -> {
            try {
                return restClient.get()
                        .uri(PATH_ME)
                        .header("Authorization", "Bearer " + accessToken)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> { /* 본문에서 판별 */ })
                        .body(JsonNode.class);
            } catch (HttpStatusCodeException e) {
                throw mapHttpError("login-me", e);
            } catch (ResourceAccessException e) {
                throw new TossLoginException("토스 login-me I/O timeout: " + e.getMessage(), false, e);
            }
        });
        JsonNode s = requireSuccess(root, "login-me");
        return toUserInfo(s);
    }

    @Override
    public void unlinkByAccessToken(String accessToken) {
        withRetry("remove-by-access-token", () -> {
            try {
                restClient.post()
                        .uri(PATH_REMOVE_BY_TOKEN)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .toBodilessEntity();
                return null;
            } catch (HttpStatusCodeException e) {
                throw mapHttpError("remove-by-access-token", e);
            } catch (ResourceAccessException e) {
                throw new TossLoginException("토스 unlink I/O timeout: " + e.getMessage(), false, e);
            }
        });
    }

    @Override
    public void unlinkByUserKey(long userKey) {
        postJson(PATH_REMOVE_BY_USER_KEY, Map.of("userKey", userKey));
    }

    // ---- helpers ----

    private JsonNode postJson(String path, Object body) {
        return withRetry(path, () -> {
            try {
                return restClient.post()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> { /* 본문에서 판별 */ })
                        .body(JsonNode.class);
            } catch (HttpStatusCodeException e) {
                throw mapHttpError(path, e);
            } catch (ResourceAccessException e) {
                throw new TossLoginException("토스 " + path + " I/O timeout: " + e.getMessage(), false, e);
            }
        });
    }

    /** invalidGrant이 아닌 실패만 재시도(지수 백오프). */
    private JsonNode withRetry(String label, java.util.function.Supplier<JsonNode> call) {
        int max = Math.max(1, properties.getMaxRetries());
        TossLoginException last = null;
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                return call.get();
            } catch (TossLoginException e) {
                last = e;
                if (e.isInvalidGrant() || attempt == max) throw e;
                long backoff = (long) Math.pow(2, attempt) * 100L;
                log.warn("[TOSS] {} 실패 retry {}/{} after {}ms: {}", label, attempt, max, backoff, e.getMessage());
                sleep(backoff);
            }
        }
        throw last;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new TossLoginException("토스 호출 중 인터럽트", false, ie);
        }
    }

    private TossLoginException mapHttpError(String label, HttpStatusCodeException e) {
        boolean invalidGrant = e.getResponseBodyAsString().contains("invalid_grant");
        return new TossLoginException(
                "토스 " + label + " HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(),
                invalidGrant, e);
    }

    /** resultType/error 본문을 검사하고 success 노드를 반환. */
    private JsonNode requireSuccess(JsonNode root, String label) {
        if (root == null) {
            throw new TossLoginException("토스 " + label + " 응답 없음", false);
        }
        // { "error": "invalid_grant" }
        JsonNode error = root.get("error");
        if (error != null && error.isTextual()) {
            boolean invalidGrant = "invalid_grant".equals(error.asText());
            throw new TossLoginException("토스 " + label + " error: " + error.asText(), invalidGrant);
        }
        // { "resultType": "FAIL", "error": { errorCode, reason } }
        JsonNode resultType = root.get("resultType");
        if (resultType != null && "FAIL".equals(resultType.asText())) {
            String reason = error != null ? error.toString() : "unknown";
            throw new TossLoginException("토스 " + label + " FAIL: " + reason, false);
        }
        JsonNode success = root.get("success");
        if (success == null || success.isNull()) {
            throw new TossLoginException("토스 " + label + " success 누락: " + root, false);
        }
        return success;
    }

    private TossToken toToken(JsonNode s) {
        return new TossToken(
                text(s, "tokenType"),
                text(s, "accessToken"),
                text(s, "refreshToken"),
                s.path("expiresIn").asLong(0),
                text(s, "scope")
        );
    }

    private TossUserInfo toUserInfo(JsonNode s) {
        List<String> terms = new ArrayList<>();
        JsonNode agreed = s.get("agreedTerms");
        if (agreed != null && agreed.isArray()) {
            agreed.forEach(t -> terms.add(t.asText()));
        }
        return new TossUserInfo(
                s.path("userKey").asLong(0),
                text(s, "scope"),
                terms,
                cryptoService.decrypt(text(s, "name")),
                cryptoService.decrypt(text(s, "phone")),
                cryptoService.decrypt(text(s, "birthday")),
                cryptoService.decrypt(text(s, "ci")),
                cryptoService.decrypt(text(s, "gender")),
                cryptoService.decrypt(text(s, "nationality")),
                cryptoService.decrypt(text(s, "email"))
        );
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
