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
                .predictTime1(seconds(a.predictMin1(), a.predictSec1()))
                .congestionNum1(congestion(a.crowded1()))
                .arrivalMsg2(message(a.predictMin2(), a.locationNo2(), a.flag()))
                .predictTime2(seconds(a.predictMin2(), a.predictSec2()))
                .congestionNum2(congestion(a.crowded2()))
                .stopsAway1(stopsAway(a.predictMin1(), a.locationNo1()))
                .stopsAway2(stopsAway(a.predictMin2(), a.locationNo2()))
                .vehicleId1(vehicleId(a.predictMin1(), a.vehicleId1()))
                .vehicleId2(vehicleId(a.predictMin2(), a.vehicleId2()))
                .build();
    }

    /**
     * 버스가 있을 때만(분이 있을 때만). 초 단위 칸은 분과 1분 안에서 맞을 때만 쓴다 — 값이 든 실응답을 아직 보지 못해
     * (2026-11-10 밤, 칸 이름만 확인) 분과 어긋난 초는 믿지 않고 분 × 60 으로 돌아간다. 문구는 분 기준이라 둘이 엇갈리면 안 된다.
     */
    private static Integer seconds(Integer minutes, Integer sourceSeconds) {
        if (minutes == null) {
            return null;
        }
        int fromMinutes = minutes * 60;
        return sourceSeconds != null && sourceSeconds >= 0 && Math.abs(sourceSeconds - fromMinutes) <= 60
                ? sourceSeconds : fromMinutes;
    }

    private static Integer stopsAway(Integer minutes, Integer locationNo) {
        return minutes == null || locationNo == null || locationNo < 0 ? null : locationNo;
    }

    private static String vehicleId(Integer minutes, String raw) {
        return minutes == null || raw == null || raw.isBlank() || "0".equals(raw) ? null : raw;
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
