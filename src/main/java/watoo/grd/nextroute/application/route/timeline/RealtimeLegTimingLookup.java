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
    /**
     * @param boardingStop 승차 stop occurrence. leg 에 stop 정보가 없으면 <b>null</b> 일 수 있다(구현체는 null 방어).
     * @param plannedBoardAt 정적 계획 승차시각(non-null; composer 가 절대시각 없는 leg 은 미리 제외)
     */
    Optional<RealtimeLegTiming> timingFor(RoutingLeg leg, StopOccurrence boardingStop, Instant plannedBoardAt);
}
