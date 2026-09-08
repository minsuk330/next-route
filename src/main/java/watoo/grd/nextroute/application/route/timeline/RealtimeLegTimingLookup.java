package watoo.grd.nextroute.application.route.timeline;

import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;

import java.time.Instant;
import java.util.Optional;

/**
 * 검증된 실시간 지원 leg 의 실시간 시각 조회(주입). 프로덕션은 기존 실시간/ML 보강 경계가 채우고,
 * 미지원/미매핑 leg 은 empty 를 돌려 composer 가 정적으로 처리한다. 순수 조합 로직을 테스트 가능하게 유지한다.
 */
@FunctionalInterface
public interface RealtimeLegTimingLookup {
    Optional<RealtimeLegTiming> timingFor(RoutingLeg leg, StopOccurrence boardingStop, Instant plannedBoardAt);
}
