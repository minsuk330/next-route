package watoo.grd.nextroute.domain.journey.entity;

/**
 * 재탐색 제안의 종류.
 *
 * <p>값 목록은 {@code reroute_proposal_kind_check} 가 소유한다 — 여기 상수를 늘려도 DB 가 받지
 * 않으면 삽입에서 터진다. 그것이 의도다. 종류를 늘리는 일은 마이그레이션·후보 계약·수락 로직이
 * 함께 바뀌는 호환성 변경이라 한 곳에서 조용히 늘어나면 안 된다.
 *
 * <p>이 이름은 {@code RerouteCandidateApply} 의 {@code @JsonSubTypes} discriminator 값과
 * <b>같은 문자열</b>이다. 세 곳(DB check·이 enum·JSON 이름)이 어긋나면 어느 것이 진짜인지
 * 아무도 모른다.
 */
public enum RerouteProposalKind {

    /** 같은 승·하차 구간에서 다른 노선을 고른다. leg 구조는 그대로다. */
    ROUTE_OPTION,

    /** 남은 구간을 다른 경로로 통째 갈아끼운다. */
    REMAINING_PATH
}
