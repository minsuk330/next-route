package watoo.grd.nextroute.application.route.timeline;

/** ETA 합성 시 한 편(leg)의 연결 가능성. */
public enum Feasibility {
    /** userReadyAt + buffer 이상 출발 → 탈 수 있음. */
    FEASIBLE,
    /** 여유 부족/이미 지나감 → 이 편은 못 탐(재검색 대상). */
    MISSED,
    /** 운행 취소 확인 → 정적 fallback 금지, 후보 제외. */
    CANCELLED,
    /** 재평가/재검색 후에도 탈 수 있는 편이 없음 → 후보 제외(P2-F). */
    NO_CATCHABLE
}
