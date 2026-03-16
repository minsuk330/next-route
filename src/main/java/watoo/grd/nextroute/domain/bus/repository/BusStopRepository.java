package watoo.grd.nextroute.domain.bus.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.bus.entity.BusStop;

import java.util.Optional;

public interface BusStopRepository extends JpaRepository<BusStop, Long> {

	Optional<BusStop> findByStopId(String stopId);

	Optional<BusStop> findByArsId(String arsId);

	boolean existsByStopId(String stopId);
}
