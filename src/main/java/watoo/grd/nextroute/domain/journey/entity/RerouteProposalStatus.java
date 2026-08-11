package watoo.grd.nextroute.domain.journey.entity;

/**
 * 제안의 수명 상태. 값 목록은 {@code reroute_proposal_status_check} 가 소유한다.
 *
 * <p>{@link #ACTIVE} 만 부분 unique 의 대상이다 — 나머지 상태의 행은 같은 환승에 몇 개가 쌓여도
 * 새 제안을 막지 않는다.
 */
public enum RerouteProposalStatus {

    /** 살아 있다. 같은 환승에 한 번에 하나뿐이다. */
    ACTIVE,

    /** 사용자가 골랐다. 새 개정본이 이 제안에서 나왔다(⑤). */
    ACCEPTED,

    /** TTL 이 지났다. lazy 전이나 배치 sweep 이 넘긴다. */
    EXPIRED,

    /** 새 제안이나 개정본 변경이 자리를 가져갔다. */
    SUPERSEDED
}
