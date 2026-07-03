package watoo.grd.nextroute.domain.notification.entity;

/**
 * 목표 도착시간 출발 알림 체크포인트 상태머신.
 * WAITING_DEPARTURE 진입 시 nextActionAt=NULL 로 두어 체크 스케줄러 스캔에서 제외한다(dispatcher 소유).
 */
public enum RouteAlertPhase {
    /** 등록 직후 — first check 대기(nextActionAt=firstCheckAt). */
    WAITING_FIRST_CHECK,
    /** first check 완료 — DEPARTURE 발송 대기(nextActionAt=NULL, dispatcher 소유). */
    WAITING_DEPARTURE,
    /** DEPARTURE 발송 완료 — final check 대기(nextActionAt=finalCheckAt). */
    WAITING_FINAL_CHECK,
    /** 종결. */
    COMPLETED
}
