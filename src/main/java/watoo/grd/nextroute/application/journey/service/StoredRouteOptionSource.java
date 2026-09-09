package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;

import java.util.Comparator;
import java.util.List;

/**
 * journey 시작 때 저장한 {@code journey_leg_route_option} 을 대안 후보로 내는 기본 출처.
 *
 * <p>{@link RouteOptionCandidateProvider} 가 직접 하던 조회를 그대로 옮긴 것(optionIndex 오름차순 정렬
 * 보존) — 행위가 바뀌지 않는다. 저장 조회는 장애 상태가 없어 정상/빈 목록 모두 {@code OK} 다.
 * {@code deadline} 은 저장 조회에 쓰이지 않는다(OTP 등 외부 출처를 위한 계약).
 */
@Component
@RequiredArgsConstructor
public class StoredRouteOptionSource implements RerouteRouteOptionSource {

    private final JourneyLegRouteOptionRepository routeOptionRepository;

    @Override
    public SourceOutcome optionsFor(JourneyLeg waitLeg, RoutingDeadline deadline) {
        List<JourneyLegRouteOption> options = routeOptionRepository
                .findByJourneyLegIdInAndDeletedAtIsNull(List.of(waitLeg.getId())).stream()
                .sorted(Comparator.comparingInt(JourneyLegRouteOption::getOptionIndex))
                .toList();
        return SourceOutcome.ok(options);
    }
}
