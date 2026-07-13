package watoo.grd.nextroute.domain.notification.entity;

/** 경로 알림 발송 outbox 상태. 발송/재시도는 row 단위로 추적한다. */
public enum RouteNotificationStatus {
    PENDING,
    PROCESSING,
    SENT,
    /** permanent 실패(종결). 재시도/requeue 대상 아님. */
    FAILED,
    /** transient 재시도 소진 → 수동 개입 대기. admin requeue 로 PENDING 복귀 가능. */
    DEAD,
    CANCELED
}
