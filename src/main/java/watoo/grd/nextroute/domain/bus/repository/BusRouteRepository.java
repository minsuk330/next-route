package watoo.grd.nextroute.domain.bus.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;

import java.util.List;
import java.util.Optional;

public interface BusRouteRepository extends JpaRepository<BusRoute, Long> {

	Optional<BusRoute> findByRouteId(String routeId);

	boolean existsByRouteId(String routeId);

	List<BusRoute> findByRouteNameIn(List<String> routeNames);
}
