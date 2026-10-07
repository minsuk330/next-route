package watoo.grd.nextroute.application.journey.dto;

/**
 * 링 한 건이 어느 고정 세대의 것인지와, 그 단계의 계획 역 체인.
 *
 * <p>fencing 축이 넷인 이유는 재탐색 때문이다. 개정본이 오르면 같은 {@code legIndex} 의 단계가
 * 복제되고, 계승은 {@code bindingVersion} 을 0 으로 되돌린다. 둘 중 하나만 보면 <b>옛 worker 가
 * 새 링을 덮는다</b>.
 *
 * <p>{@code startSequence} 는 직전 링이 없을 때 처음 향할 역이다. 보통은 승차 다음 역(1)이다 — 이동
 * 시작 관측으로 고정됐으면 열차는 이미 승차역을 떠났다. 사용자가 역에서 고르고 아직 안 움직였으면
 * (#304) 열차가 승차역에 오기 전일 수 있어 <b>승차역(0)</b>에서 시작한다.
 */
public record SubwayRingContext(
        Long journeyId,
        int routeRevision,
        int legIndex,
        int bindingVersion,
        SubwayRingChain chain,
        int startSequence
) {

    public SubwayRingContext(Long journeyId, int routeRevision, int legIndex, int bindingVersion,
                             SubwayRingChain chain) {
        this(journeyId, routeRevision, legIndex, bindingVersion, chain, 1);
    }
}
