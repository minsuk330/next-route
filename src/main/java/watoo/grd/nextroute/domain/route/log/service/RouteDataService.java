package watoo.grd.nextroute.domain.route.log.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.route.log.entity.RouteSearchLog;
import watoo.grd.nextroute.domain.route.log.repository.RouteSearchLogRepository;

@Service
@RequiredArgsConstructor
public class RouteDataService {

    private final RouteSearchLogRepository routeSearchLogRepository;

    @Transactional
    public void save(RouteSearchLog log) {
        routeSearchLogRepository.save(log);
    }
}
