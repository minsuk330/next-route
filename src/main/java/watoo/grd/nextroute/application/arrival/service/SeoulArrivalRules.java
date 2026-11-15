package watoo.grd.nextroute.application.arrival.service;

/**
 * 서울 도착 원본을 읽는 규칙. 노선 단위({@code getArrInfoByRoute})와 정류장 단위({@code getStationByUid}) 응답이 같은
 * 칸을 같은 뜻으로 주므로 한 곳에 둔다.
 */
final class SeoulArrivalRules {

    private SeoulArrivalRules() {
    }

    /** 서울은 버스가 없으면 차량 id 를 {@code "0"} 으로 준다({@code BusRouteWatcher} 와 같은 규칙). */
    static String vehicleId(String raw) {
        return raw == null || raw.isBlank() || "0".equals(raw.trim()) ? null : raw.trim();
    }

    /**
     * 문구가 운행종료면 차량 id 가 와도 버스가 없는 것으로 본다. 막차가 이 정류장에 막 닿은 순간 서울은 {@code 운행종료} 와 함께
     * 그 차량 id, {@code traTime} 몇 초, 구간 순번 = 정류장 순번을 같이 준다(2026-11-16 121000009 420번 실응답). 문구와
     * 시간이 어긋나지 않게 시간, 남은 정류장, 차량 id 를 모두 비운다(사용자 결정 2026-11-16).
     */
    static String vehicleId(String raw, String arrivalMsg) {
        return ended(arrivalMsg) ? null : vehicleId(raw);
    }

    static boolean ended(String arrivalMsg) {
        return arrivalMsg != null && arrivalMsg.contains("운행종료");
    }

    /**
     * 요청 정류장 순번 − 버스의 현재 구간 순번 = 도착 문구의 {@code [N번째 전]}(2026-11-10 실응답 9건으로 확인. 곧 도착이면 0).
     * 버스가 없으면(차량 id 없음, 구간 순번 0) 모른다. 음수(정류장을 지나친 값)도 모른다.
     */
    static Integer stopsAway(Integer ord, Integer sectionOrder, String vehicleId) {
        if (ord == null || vehicleId == null || sectionOrder == null || sectionOrder <= 0) {
            return null;
        }
        int away = ord - sectionOrder;
        return away < 0 ? null : away;
    }

    /**
     * 버스가 있을 때의 남은 초. 차량이 없는 행(차량 id "0": 출발대기, 운행종료 등, 또는 운행종료 문구)의 {@code traTime} 은
     * 남은 초가 아니다(운행종료 행은 0) — 그대로 두면 정렬에서 "0분"으로 맨 위에 온다. 노선 단위와 정류장 단위 공통
     * (사용자 결정 2026-11-16).
     */
    static Integer predictSeconds(Integer traTime, String vehicleId) {
        return vehicleId == null || traTime == null || traTime < 0 ? null : traTime;
    }
}
