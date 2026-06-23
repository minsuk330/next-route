package watoo.grd.nextroute.infrastructure.adapter.out.api.toss;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import watoo.grd.nextroute.application.notification.exception.TossMessengerException;
import watoo.grd.nextroute.application.notification.port.out.TossMessengerPort;

import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class TossMessengerAdapter implements TossMessengerPort {

    private static final String PATH_SEND =
            "/api-partner/v1/apps-in-toss/messenger/send-message";

    /** 재시도 가치 없는 errorCode(권한·템플릿·동의·파라미터). */
    private static final Set<String> PERMANENT_CODES = Set.of(
            "INVALID_PARAMETER", "FORBIDDEN", "UNAUTHORIZED",
            "TEMPLATE_NOT_FOUND", "AGREEMENT_REQUIRED", "NOT_AGREED");

    private final RestClient restClient;

    public TossMessengerAdapter(@Qualifier("tossRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void sendMessage(long tossUserKey, String templateSetCode, Map<String, Object> context) {
        try {
            JsonNode root = restClient.post()
                    .uri(PATH_SEND)
                    .header("x-toss-user-key", String.valueOf(tossUserKey))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("templateSetCode", templateSetCode, "context", context))
                    .retrieve()                       // 4xx/5xx → RestClientResponseException
                    .body(JsonNode.class);

            if (root != null && "FAIL".equals(text(root, "resultType"))) {
                String code = root.path("error").path("errorCode").asText("");
                throw new TossMessengerException(
                        "토스 send-message FAIL: " + code, isPermanentCode(code));
            }
        } catch (RestClientResponseException e) {
            int s = e.getStatusCode().value();
            boolean permanent = (s == 400 || s == 401 || s == 403); // 429/5xx = transient
            throw new TossMessengerException(
                    "토스 send-message HTTP " + s + ": " + e.getResponseBodyAsString(), permanent, e);
        } catch (ResourceAccessException e) {                         // timeout/IO = transient
            throw new TossMessengerException(
                    "토스 send-message I/O: " + e.getMessage(), false, e);
        }
    }

    private boolean isPermanentCode(String code) {
        return code != null && PERMANENT_CODES.contains(code);
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
