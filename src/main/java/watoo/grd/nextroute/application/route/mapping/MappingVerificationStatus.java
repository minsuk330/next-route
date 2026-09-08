package watoo.grd.nextroute.application.route.mapping;

/**
 * GTFS↔공급자 ID 매핑의 검증 상태. 번호·이름만 같은 자동 매칭은 금지 —
 * 지역/기관/승하차/순서/방향이 검증된 것만 VERIFIED 로 실시간에 쓴다.
 */
public enum MappingVerificationStatus {
    /** 검증 완료 — 실시간/ML 적용 허용. */
    VERIFIED,
    /** 후보이나 미검증(모호·부분) — 실시간 미적용, STATIC 유지. */
    REVIEW
}
