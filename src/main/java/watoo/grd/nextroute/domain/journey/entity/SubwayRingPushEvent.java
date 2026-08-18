package watoo.grd.nextroute.domain.journey.entity;

/**
 * ActivityKit 이 받는 {@code aps.event}.
 *
 * <p>값이 <b>소문자</b>인 것은 APNs 계약이다. 대문자로 보내면 앱이 이벤트를 못 알아보고 갱신이
 * 조용히 무시된다 — 그래서 열거형 이름과 전선에 나가는 값을 분리해 둔다.
 *
 * <p>{@code start}(push-to-start, iOS 17.2+)는 범위 밖이다. 앱이 Activity 를 먼저 만든다는
 * 전제이고, 미리 넣으면 쓰지 않는 값이 계약처럼 굳는다.
 */
public enum SubwayRingPushEvent {

    /** 진행 갱신. */
    UPDATE("update"),

    /**
     * Activity 종료.
     *
     * <p>이것이 유실되면 잠금화면에 <b>죽은 안내가 그대로 남는다</b>. 그래서 종료는 언제나
     * 중요 이벤트이고 유효기간 안에서 반드시 재시도한다.
     */
    END("end");

    private final String wireValue;

    SubwayRingPushEvent(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static SubwayRingPushEvent fromWire(String value) {
        for (SubwayRingPushEvent event : values()) {
            if (event.wireValue.equals(value)) {
                return event;
            }
        }
        throw new IllegalArgumentException("알 수 없는 Live Activity event");
    }
}
