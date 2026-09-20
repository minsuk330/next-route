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

    /**
     * <b>폐기됨</b>(journey-reroute-nextstop PR-5). 같은 승·하차 구간에서 다른 노선을 고르던 제안.
     *
     * <p>값을 <b>지우지 않는 이유</b>는 이 enum 이 DB 컬럼을 <b>읽는</b> 어휘이기 때문이다. V42 의
     * check 제약은 아직 이 값을 받아들이고, 그 값으로 저장된 옛 행이 남아 있을 수 있다. 여기서
     * 지우면 그 행을 읽는 순간 터진다 — 만드는 쪽을 없애면 어휘는 자연히 죽는다.
     *
     * <p>이 종류로 <b>새 제안을 만들지 않는다</b>. 대응하는 적용 재료({@code RouteOptionApply})도
     * 함께 사라졌으므로, 옛 행의 후보는 "모르는 봉투"로 거절된다.
     */
    ROUTE_OPTION,

    /** 남은 구간을 다른 경로로 통째 갈아끼운다. */
    REMAINING_PATH
}
