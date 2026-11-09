package watoo.grd.nextroute.application.arrival.service;

import watoo.grd.nextroute.application.arrival.dto.BusArrivalResponse;
import watoo.grd.nextroute.application.arrival.dto.TagoBusArrival;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * TAGO(버스 한 대 = item 하나)를 서울 응답과 같은 의미의 {@link BusArrivalResponse} 한 건으로 모은다.
 *
 * <ul>
 *   <li>도착 시간 순으로 앞 두 대를 첫 번째/두 번째 버스로.</li>
 *   <li>{@code predictTime}: TAGO arrtime 이 이미 초라 그대로(서울 traTime 과 같은 단위).</li>
 *   <li>{@code arrivalMsg}: 60초 이하 {@code 곧 도착}, 그 외 {@code N분후[M번째 전]}(분은 내림). 두 번째 버스가 없으면
 *       {@code 도착 정보 없음}.</li>
 *   <li>{@code congestionNum}: TAGO 에 혼잡도가 없어 0(정보 없음).</li>
 * </ul>
 * 다가오는 버스가 0대면 응답도 0건(서울의 OK-빈 결과와 같은 의미).
 */
final class TagoArrivalMapper {

    static final String MSG_SOON = "곧 도착";
    static final String MSG_NONE = "도착 정보 없음";

    private TagoArrivalMapper() {
    }

    static Optional<BusArrivalResponse> toResponse(String routeId, List<TagoBusArrival> buses) {
        List<TagoBusArrival> sorted = buses.stream()
                .filter(b -> b.arrSeconds() != null)
                .sorted(Comparator.comparingInt(TagoBusArrival::arrSeconds))
                .toList();
        if (sorted.isEmpty()) {
            return Optional.empty();
        }
        TagoBusArrival first = sorted.get(0);
        TagoBusArrival second = sorted.size() > 1 ? sorted.get(1) : null;
        return Optional.of(BusArrivalResponse.builder()
                .routeId(routeId)
                .arrivalMsg1(message(first))
                .predictTime1(first.arrSeconds())
                .congestionNum1(0)
                .arrivalMsg2(second == null ? MSG_NONE : message(second))
                .predictTime2(second == null ? null : second.arrSeconds())
                .congestionNum2(0)
                .stopsAway1(stopsAway(first))
                .stopsAway2(second == null ? null : stopsAway(second))
                .build());
    }

    private static Integer stopsAway(TagoBusArrival bus) {
        return bus.stopsAway() == null || bus.stopsAway() < 0 ? null : bus.stopsAway();
    }

    private static String message(TagoBusArrival bus) {
        int seconds = bus.arrSeconds();
        if (seconds <= 60) {
            return MSG_SOON;
        }
        int minutes = seconds / 60;
        return bus.stopsAway() == null
                ? minutes + "분후"
                : minutes + "분후[" + bus.stopsAway() + "번째 전]";
    }
}
