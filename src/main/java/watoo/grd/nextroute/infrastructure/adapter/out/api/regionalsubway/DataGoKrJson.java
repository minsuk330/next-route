package watoo.grd.nextroute.infrastructure.adapter.out.api.regionalsubway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalSourceException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * data.go.kr JSON 응답 공통 처리. {@code RegionalBusRouteSourceAdapter} 와 같은 규칙.
 *
 * <ul>
 *   <li>포털 공통 오류 {@code OpenAPI_ServiceResponse.cmmMsgHeader.returnReasonCode}: 20/22/30~33(미등록 키, 한도 초과)은 그날 계속 실패 → blocking</li>
 *   <li>시간 초과 같은 I/O 오류 → retryable</li>
 *   <li>목록이 한 건이면 배열이 아니라 객체, 0건이면 {@code "items": ""}</li>
 * </ul>
 */
final class DataGoKrJson {

    private static final Set<String> BLOCKING_REASON_CODES = Set.of("20", "22", "30", "31", "32", "33");

    private DataGoKrJson() {
    }

    static JsonNode call(RestClient restClient, ObjectMapper objectMapper, URI uri) {
        String body;
        try {
            body = restClient.get().uri(uri).exchange((req, res) ->
                    new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (ResourceAccessException e) {
            throw RegionalSourceException.retryable("call failed: " + redact(e.getMessage()));
        } catch (Exception e) {
            throw RegionalSourceException.failed("call failed: " + redact(e.getMessage()));
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw RegionalSourceException.failed("non-JSON response");
        }
        if (root == null || root.isMissingNode()) {
            throw RegionalSourceException.failed("empty response");
        }
        JsonNode portalError = root.path("OpenAPI_ServiceResponse").path("cmmMsgHeader");
        if (!portalError.isMissingNode()) {
            String reason = portalError.path("returnReasonCode").asText("");
            String message = "portal error reasonCode=" + reason + " " + portalError.path("errMsg").asText("");
            throw BLOCKING_REASON_CODES.contains(reason)
                    ? RegionalSourceException.blocking(message)
                    : RegionalSourceException.failed(message);
        }
        return root;
    }

    /** {@code body.items.item}: {@code items} 가 객체가 아니면 0건, {@code item} 이 객체면 1건. */
    static <T> List<T> items(JsonNode body, Function<JsonNode, T> mapper) {
        JsonNode items = body.path("items");
        if (!items.isObject()) {
            return List.of();
        }
        JsonNode item = items.path("item");
        List<T> out = new ArrayList<>();
        if (item.isArray()) {
            item.forEach(i -> out.add(mapper.apply(i)));
        } else if (item.isObject()) {
            out.add(mapper.apply(item));
        }
        return out;
    }

    /** 예외 메시지에 요청 URL(serviceKey 포함)이 실린다. 로그로 키가 새지 않게 가린다. */
    static String redact(String message) {
        return message == null ? null : message.replaceAll("(?i)serviceKey=[^&\\s\"']+", "serviceKey=***");
    }

    static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw RegionalSourceException.blocking("api key not configured");
        }
    }
}
