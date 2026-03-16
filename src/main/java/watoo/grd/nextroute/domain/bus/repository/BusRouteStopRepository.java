package watoo.grd.nextroute.domain.bus.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;

import java.util.List;

public interface BusRouteStopRepository extends JpaRepository<BusRouteStop, Long> {

	List<BusRouteStop> findByRouteIdOrderBySeq(String routeId);

	void deleteByRouteId(String routeId);
}
