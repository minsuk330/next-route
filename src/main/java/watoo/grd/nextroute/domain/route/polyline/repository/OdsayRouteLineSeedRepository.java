package watoo.grd.nextroute.domain.route.polyline.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.route.polyline.entity.OdsayRouteLineSeed;

import java.util.List;

public interface OdsayRouteLineSeedRepository extends JpaRepository<OdsayRouteLineSeed, Long> {

    List<OdsayRouteLineSeed> findByEnabledTrue();
}
