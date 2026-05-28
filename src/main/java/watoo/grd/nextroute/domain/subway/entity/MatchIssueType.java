package watoo.grd.nextroute.domain.subway.entity;

public enum MatchIssueType {
    /** mappable station 부재 → 매핑 불가 */
    MAPPING_MISSING,
    /** 시간표 < 이벤트 (이벤트 잔여) — V1 row-level / V2 group-level 진단 */
    EXTRA_RAW_EVENT,
    /** 시간표 > 이벤트 (시간표 잔여) — V1 row-level. V2에서는 row-level 미발행 */
    NO_RAW_EVENT,
    /** V2: ordinal pair 시간창(±1800s) 초과 — group-level reject */
    MATCH_REJECTED_TIME_DISTANCE,
    /** V2: timetable_count != event_count — group-level. Phase C 입력 */
    COUNT_MISMATCH,
    /** V2: known-known 행선지 불일치 — group-level reject */
    DESTINATION_MISMATCH
}
