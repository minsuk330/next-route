package watoo.grd.nextroute.application.arrival.service;

import watoo.grd.nextroute.application.arrival.dto.BusArrivalResponse;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;

/**
 * 경기 도착(GBIS)을 서울 응답과 <b>같은 의미</b>의 {@link BusArrivalResponse} 로 옮긴다. 프론트는 provider 를 모른다.
 *
 * <ul>
 *   <li>{@code predictTime}: 서울은 traTime(초). 경기는 분 → ×60.</li>
 *   <li>{@code arrivalMsg}: 서울 arrmsg 형식에 맞춰 만든다. 1분 이하 {@code 곧 도착},
 *       그 외 {@code N분후[M번째 전]}. 버스가 없으면 노선 상태(flag)로 {@code 운행종료}/{@code 출발대기},
 *       모르는 상태는 {@code 도착 정보 없음}.</li>
 *   <li>{@code congestionNum}: 서울 혼잡도 코드(3 여유·4 보통·5 혼잡·6 매우혼잡, 0 정보없음)로.
 *       경기 crowded 1~4 → 3~6.</li>
 * </ul>
 */
final class GyeonggiArrivalMapper {

    static final String MSG_SOON = "곧 도착";
    static final String MSG_ENDED = "운행종료";
    static final String MSG_WAITING = "출발대기";
    static final String MSG_NONE = "도착 정보 없음";

    private GyeonggiArrivalMapper() {
    }

    static BusArrivalResponse toResponse(GyeonggiBusArrival a) {
        return BusArrivalResponse.builder()
                .routeId(a.routeId())
                .arrivalMsg1(message(a.predictMin1(), a.locationNo1(), a.flag()))
                .predictTime1(seconds(a.predictMin1()))
                .congestionNum1(congestion(a.crowded1()))
                .arrivalMsg2(message(a.predictMin2(), a.locationNo2(), a.flag()))
                .predictTime2(seconds(a.predictMin2()))
                .congestionNum2(congestion(a.crowded2()))
                .build();
    }

    private static Integer seconds(Integer minutes) {
        return minutes == null ? null : minutes * 60;
    }

    private static String message(Integer minutes, Integer stopsAway, String flag) {
        if (minutes == null) {
            if ("STOP".equals(flag)) {
                return MSG_ENDED;
            }
            if ("WAIT".equals(flag)) {
                return MSG_WAITING;
            }
            return MSG_NONE;
        }
        if (minutes <= 1) {
            return MSG_SOON;
        }
        return stopsAway == null
                ? minutes + "분후"
                : minutes + "분후[" + stopsAway + "번째 전]";
    }

    private static Integer congestion(Integer crowded) {
        if (crowded == null || crowded < 1 || crowded > 4) {
            return 0;
        }
        return crowded + 2;
    }
}
