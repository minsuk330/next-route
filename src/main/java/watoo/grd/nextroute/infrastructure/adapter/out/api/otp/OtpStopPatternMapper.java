package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.routing.StopPattern;

import java.util.ArrayList;
import java.util.List;

/**
 * OTP {@code stop(id).patterns} 응답 JSON → {@link StopPattern} 목록. 순수 매핑(HTTP·오류분류는 어댑터).
 *
 * <p>{@code stops} 순서를 <b>그대로</b> 보존한다 — 방향 판정(승차 index &lt; 하차 index)이 이 순서에
 * 매달린다. {@code stop} 이 없거나(모르는 id) {@code patterns} 가 배열이 아니면 빈 목록.
 */
@Component
public class OtpStopPatternMapper {

    public List<StopPattern> map(JsonNode root) {
        JsonNode patterns = root.path("data").path("stop").path("patterns");
        if (!patterns.isArray()) {
            return List.of();
        }
        List<StopPattern> out = new ArrayList<>();
        for (JsonNode pattern : patterns) {
            JsonNode route = pattern.path("route");
            List<String> stops = new ArrayList<>();
            for (JsonNode stop : pattern.path("stops")) {
                String gtfsId = text(stop.path("gtfsId"));
                if (gtfsId != null) {
                    stops.add(gtfsId);
                }
            }
            out.add(new StopPattern(
                    text(route.path("gtfsId")),
                    text(route.path("shortName")),
                    directionId(pattern.path("directionId")),
                    stops));
        }
        return out;
    }

    private static Integer directionId(JsonNode node) {
        return node.isInt() ? node.asInt() : null;
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
