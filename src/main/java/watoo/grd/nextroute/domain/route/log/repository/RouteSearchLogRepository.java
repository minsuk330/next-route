package watoo.grd.nextroute.domain.route.log.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.route.log.entity.RouteSearchLog;

public interface RouteSearchLogRepository extends JpaRepository<RouteSearchLog, Long> {
}
