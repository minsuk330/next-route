package watoo.grd.nextroute.application.journey.dto;

/**
 * 이 지하철 구간을 실시간으로 추적할 수 있는지(Dynamic Island 정상화, 결정 9).
 *
 * <p>후보 목록이 빈 것만으로는 "지금 열차가 없다"와 "이 노선은 실시간이 없다"를 가르지 못한다. 앱은 후자면 열차 선택과
 * 링을 아예 띄우지 않는다 — 고르더라도 서버가 그 열차를 따라갈 원천이 없다.
 *
 * @param legIndex  판정한 지하철 탑승 구간
 * @param trackable 실시간 열차를 고를 수 있으면 true
 * @param reason    false 일 때 이유. true 면 null
 */
public record SubwayTrackabilityView(int legIndex, boolean trackable, Reason reason) {

    public enum Reason {
        /** 서울 실시간 원천이 다루지 않는 노선(지방·인천1·2·김포골드 등). */
        NO_REALTIME_LINE,
        /** 방향 코드를 풀 수 없다 — 반대 방향 열차를 집지 않으려 고르게 하지 않는다. */
        UNKNOWN_DIRECTION,
        /** 승차역 id 가 없다. */
        NO_STATION
    }

    public static SubwayTrackabilityView yes(int legIndex) {
        return new SubwayTrackabilityView(legIndex, true, null);
    }

    public static SubwayTrackabilityView no(int legIndex, Reason reason) {
        return new SubwayTrackabilityView(legIndex, false, reason);
    }
}
