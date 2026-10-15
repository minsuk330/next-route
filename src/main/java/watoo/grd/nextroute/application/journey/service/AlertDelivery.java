package watoo.grd.nextroute.application.journey.service;

/**
 * 악화 알림의 발송 종류(#428). payload 의 {@code "delivery"} 값이 이 이름 그대로다.
 *
 * <ul>
 *   <li>{@link #ALERTING} — 소리·진동이 나는 알림. 경로 버전마다 위험·재탐색을 합쳐 한 번</li>
 *   <li>{@link #QUIET} — 이미 울린 뒤의 갱신. 같은 갱신 열쇠로 떠 있는 알림을 조용히 고쳐 쓴다</li>
 * </ul>
 *
 * <p>피로 정책이 꺼져 있으면 쓰지 않는다 — payload 에 {@code delivery} 칸 자체가 없어야 정책 이전과 같다.
 */
public enum AlertDelivery {
    ALERTING,
    QUIET
}
