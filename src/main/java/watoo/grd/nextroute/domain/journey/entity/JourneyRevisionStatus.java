package watoo.grd.nextroute.domain.journey.entity;

/**
 * 경로 개정본이 지금 쓰이는 것인지. 값 목록은 V35 의 {@code journey_revision_status_check} 가
 * 소유한다.
 *
 * <p>여기에는 <b>실제 안내에 쓰인 경로만</b> 들어온다. 아직 사용자가 수락하지 않은 재탐색 대안은
 * Phase 6 의 {@code reroute_proposal} 이 따로 보관한다.
 */
public enum JourneyRevisionStatus {

    /** 지금 안내 중인 경로. 여정당 하나. */
    ACTIVE,

    /** 재탐색 수락으로 밀려난 이전 경로. */
    SUPERSEDED
}
