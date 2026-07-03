package watoo.grd.nextroute.domain.notification.entity;

/** 목표 도착시간 기반 출발 알림 구독 상태. 시간(targetArrivalAt/expiresAt)만이 종결 트리거. */
public enum RouteAlertStatus {
    ACTIVE,
    DONE,
    EXPIRED,
    CANCELED
}
