package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator;
import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.port.in.SearchRouteUseCase;

/** {@link RouteTravelTimeEstimator} 기본 구현: 경로검색(ODsay+TMAP+보강)을 재사용해 최적 경로 totalTime(분)→초. */
@Component
@RequiredArgsConstructor
public class RouteSearchTravelTimeEstimator implements RouteTravelTimeEstimator {

    private final SearchRouteUseCase searchRouteUseCase;

    @Override
    public int estimateSeconds(TravelTimeQuery q) {
        RouteSearchResult result = searchRouteUseCase.search(new RouteSearchRequest(
                q.startX(), q.startY(), q.endX(), q.endY(), q.startName(), q.endName()));
        if (result.paths() == null || result.paths().isEmpty()) {
            throw new IllegalStateException("경로를 찾을 수 없습니다.");
        }
        // paths[0] = 추천 경로. totalTime 은 분 단위.
        int totalTimeMin = result.paths().get(0).info().totalTime();
        return totalTimeMin * 60;
    }
}
