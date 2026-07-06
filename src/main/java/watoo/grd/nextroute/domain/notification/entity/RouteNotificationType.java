package watoo.grd.nextroute.domain.notification.entity;

/** 경로 출발 알림 종류. */
public enum RouteNotificationType {
    /** 출발 리마인드(구독당 1회): leaveBy − userLead 도달 시. first check 가 생성. */
    DEPARTURE,
    /** 변경 알림(구독당 최대 1회): final check 재검증에서 leaveBy 가 임계 이상 당겨질 때. */
    CHANGE
}
