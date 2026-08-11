package watoo.grd.nextroute.application.journey.dto;

import java.time.Instant;

/**
 * 후보를 만들 때 필요한 여정 상태. 후보 공급자(③④)가 이것만 보고 일한다.
 *
 * <p>여정 엔티티를 그대로 넘기지 않는 이유는 공급자가 <b>트랜잭션 밖에서</b> 돌기 때문이다.
 * 준영속 엔티티를 들고 외부 API 를 부르면 그 사이 lazy 로딩이 터진다.
 */
public record RerouteContext(Long journeyId, Long userId, int routeRevision,
                             long commandVersion, int transferIndex, Instant now) {
}
