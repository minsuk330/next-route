package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.routing.RideDurationResult;

/**
 * OTP {@code routeRideDuration} 응답 JSON → {@link RideDurationResult}. 순수 매핑(HTTP·오류분류는
 * 어댑터). GraphQL {@code errors}/timeout 은 어댑터가 걸러 이 매퍼에 오지 않는다 — 여기 도달했으면
 * 응답 자체는 정상이고, 남은 판정은 <b>이 노선이 board→alight 를 실제로 지나는가</b>(미매칭=NOT_FOUND)뿐.
 *
 * <p>{@code stoptimesForPatterns} 각 항목에서 {@code pattern.route.gtfsId == routeGtfsId} 인 것을 찾아,
 * 그 대표 출발편의 <b>trip 전체 stoptimes</b> 에서 board 의 {@code scheduledDeparture} 와 alight 의
 * {@code scheduledArrival} 차이를 낸다. 방향이 반대(왕복 노선의 다른 pattern)면 board index &ge; alight
 * index 라 그 항목은 건너뛰고 다음 매칭 pattern 을 본다. 어떤 매칭에서도 board&lt;alight 유효 delta 가
 * 안 나오면 {@code NOT_FOUND}.
 */
@Component
public class OtpRouteRideDurationMapper {

    public RideDurationResult map(JsonNode root, String routeGtfsId,
                                  String boardGtfsId, String alightGtfsId) {
        JsonNode entries = root.path("data").path("stop").path("stoptimesForPatterns");
        if (!entries.isArray()) {
            return RideDurationResult.notFound();
        }
        for (JsonNode entry : entries) {
            String entryRoute = text(entry.path("pattern").path("route").path("gtfsId"));
            if (!routeGtfsId.equals(entryRoute)) {
                continue;
            }
            JsonNode stoptimes = entry.path("stoptimes");
            if (!stoptimes.isArray() || stoptimes.isEmpty()) {
                continue;
            }
            JsonNode tripStoptimes = stoptimes.get(0).path("trip").path("stoptimes");
            Integer delta = delta(tripStoptimes, boardGtfsId, alightGtfsId);
            if (delta != null) {
                return RideDurationResult.ok(delta);
            }
        }
        return RideDurationResult.notFound();
    }

    /**
     * trip 의 stoptimes 순서를 따라 board 를 먼저 만나고 그 뒤에 alight 를 만나면
     * {@code alight.scheduledArrival − board.scheduledDeparture}. 순서가 반대이거나 한쪽이 없거나
     * 시각이 null 이거나 delta 가 0 이하면 null(이 pattern 은 이 구간을 그 방향으로 안 감).
     */
    private static Integer delta(JsonNode tripStoptimes, String boardGtfsId, String alightGtfsId) {
        if (!tripStoptimes.isArray()) {
            return null;
        }
        Integer boardDeparture = null;
        for (JsonNode st : tripStoptimes) {
            String stopId = text(st.path("stop").path("gtfsId"));
            if (boardDeparture == null) {
                if (boardGtfsId.equals(stopId)) {
                    boardDeparture = intOrNull(st.path("scheduledDeparture"));
                    if (boardDeparture == null) {
                        return null;  // board 시각 없음 → 산출 불가
                    }
                }
                continue;  // board 전에 나온 alight 는 방향 반대라 무시
            }
            // board 이후 구간에서 alight 를 찾는다
            if (alightGtfsId.equals(stopId)) {
                Integer alightArrival = intOrNull(st.path("scheduledArrival"));
                if (alightArrival == null) {
                    return null;
                }
                int seconds = alightArrival - boardDeparture;
                return seconds > 0 ? seconds : null;
            }
        }
        return null;
    }

    private static Integer intOrNull(JsonNode node) {
        return node.isInt() ? node.asInt() : null;
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
