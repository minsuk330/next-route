package watoo.grd.nextroute.domain.notification.entity;

/** 경로 알림 발송 outbox 상태. 발송/재시도는 row 단위로 추적한다. */
public enum RouteNotificationStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED,
    CANCELED
}
