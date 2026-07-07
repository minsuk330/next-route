package watoo.grd.nextroute.domain.notification.entity;

/**
 * 반복/일회성 알림 구독(schedule) 상태.
 * <ul>
 *   <li>{@code ACTIVE} — 운영 중(반복=취소 전까지, 일회성=발송 성공 전까지 이월/대기).</li>
 *   <li>{@code COMPLETED} — 일회성 occurrence 발송 성공(DONE) 후 종료.</li>
 *   <li>{@code CANCELED} — 사용자 취소.</li>
 * </ul>
 * <p>terminal 실패 상태(FAILED)는 없다: too-late/운행불가는 roll-forward, 시스템오류는 retry metadata + ACTIVE 유지.
 */
public enum RouteAlertScheduleStatus {
    ACTIVE, COMPLETED, CANCELED
}
