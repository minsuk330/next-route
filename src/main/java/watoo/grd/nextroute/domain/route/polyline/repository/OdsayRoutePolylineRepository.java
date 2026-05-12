package watoo.grd.nextroute.domain.route.polyline.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.route.polyline.entity.OdsayRoutePolyline;

import java.util.Optional;

public interface OdsayRoutePolylineRepository extends JpaRepository<OdsayRoutePolyline, Long> {

    Optional<OdsayRoutePolyline> findByOdsayRouteIdAndLaneClass(String odsayRouteId, int laneClass);

    boolean existsByOdsayRouteIdAndLaneClass(String odsayRouteId, int laneClass);
}
