package watoo.grd.nextroute.application.journey.dto;

/**
 * 링 한 건이 어느 고정 세대의 것인지와, 그 단계의 계획 역 체인.
 *
 * <p>fencing 축이 넷인 이유는 재탐색 때문이다. 개정본이 오르면 같은 {@code legIndex} 의 단계가
 * 복제되고, 계승은 {@code bindingVersion} 을 0 으로 되돌린다. 둘 중 하나만 보면 <b>옛 worker 가
 * 새 링을 덮는다</b>.
 */
public record SubwayRingContext(
        Long journeyId,
        int routeRevision,
        int legIndex,
        int bindingVersion,
        SubwayRingChain chain
) {
}
