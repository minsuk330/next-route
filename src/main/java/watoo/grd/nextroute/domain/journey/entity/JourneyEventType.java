package watoo.grd.nextroute.domain.journey.entity;

/**
 * 앱이나 서버가 여정에 보내는 명령의 종류.
 *
 * <p>{@code journey_event.type} 은 varchar 이고 DB CHECK 이 없다. 값 목록을 DB 가 아니라 코드가
 * 소유하는 유일한 자리인데, 이유는 명령이 늘어나는 속도 때문이다 — 앱 기능 하나가 붙을 때마다
 * 마이그레이션을 걸면 배포 순서가 앱과 서버 사이에 묶인다.
 *
 * <p>대신 알 수 없는 이름은 상태머신이 거절한다. 저장은 되지만 상태는 바뀌지 않는다.
 */
public enum JourneyEventType {

    /** B1. 사용자가 "탔어요"를 눌렀다. */
    USER_CONFIRMED_BOARDING,

    /** B2. 사용자가 "내렸어요"를 눌렀다. */
    USER_CONFIRMED_ALIGHTING,

    /** B3. 후보 여럿 중 실제로 탈 노선을 골랐다. */
    USER_SELECTED_VEHICLE,

    /** B3. 잘못 고른 노선을 바꿨다. 이미 타고 있는 중에도 가능하다. */
    USER_CHANGED_VEHICLE,

    /** 정류장 근처에 왔다는 신호. 근거로만 쓰고 상태를 바꾸지 않는다(Phase 3). */
    STOP_PROXIMITY_DETECTED,

    /** 앱이 다시 앞으로 나왔다. 상태를 바꾸지 않는다. */
    APP_RESUMED,

    /** 재탐색 제안을 수락했다. Phase 6 이 처리한다. */
    REROUTE_ACCEPTED,

    /** 사용자가 여정을 접었다. */
    JOURNEY_CANCELED,

    /** 목적지에 도착했다. 끝나는 방식이 취소와 달라 이유를 나눠 남긴다. */
    JOURNEY_COMPLETED,

    /** 서버가 만든 명령. 앱 소식이 끊겨 안내를 접는다(Phase 4). */
    LEASE_EXPIRED
}
