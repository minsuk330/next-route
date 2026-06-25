package watoo.grd.nextroute.application.bus.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.bus.config.BusCollectorProperties;
import watoo.grd.nextroute.domain.bus.entity.WeeklyTargetRoute;
import watoo.grd.nextroute.domain.bus.repository.WeeklyTargetRouteRepository;

import java.util.List;

/**
 * 수집 대상 노선명(=BusRoute.routeName 매칭 키)의 단일 소스.
 *
 * <p>weekly_target_route 의 active 행을 우선한다. 비어 있으면(시드 전/장애)
 * application.yaml {@code collector.bus-arrival.target-route-names} 로 폴백한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TargetRouteProvider {

    private final WeeklyTargetRouteRepository repository;
    private final BusCollectorProperties properties;

    public List<String> activeRouteNames() {
        List<String> dbNames = repository.findByActiveTrueAndDeletedAtIsNull().stream()
                .map(WeeklyTargetRoute::getRouteName)
                .toList();
        if (dbNames.isEmpty()) {
            log.warn("[TargetRoute] weekly_target_route active empty — falling back to yaml target-route-names");
            return properties.getTargetRouteNames();
        }
        return dbNames;
    }
}
