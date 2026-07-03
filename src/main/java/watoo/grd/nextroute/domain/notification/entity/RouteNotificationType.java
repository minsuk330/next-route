package watoo.grd.nextroute.domain.notification.entity;

/** 경로 출발 알림 종류. */
public enum RouteNotificationType {
    /** 출발 리마인드(구독당 1회): leaveBy − userLead 도달 시. */
    DEPART_REMINDER,
    /** 앞당김 경보(cap까지): 재탐색으로 소요시간↑ → leaveBy 앞당겨질 때. */
    EARLIER_DEPARTURE
}
