package watoo.grd.nextroute.domain.bus.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import watoo.grd.nextroute.domain.bus.entity.BusRoutePolyline;

import java.util.List;
import java.util.Optional;

public interface BusRoutePolylineRepository extends JpaRepository<BusRoutePolyline, Long> {

	Optional<BusRoutePolyline> findByRouteId(String routeId);

	boolean existsByRouteId(String routeId);

	@Query(value = """
			SELECT br.route_id
			FROM bus_route br
			LEFT JOIN bus_route_polyline p ON p.route_id = br.route_id
			WHERE br.deleted_at IS NULL
			  AND p.route_id IS NULL
			ORDER BY br.route_id
			""", nativeQuery = true)
	List<String> findRouteIdsWithoutPolyline();
}
