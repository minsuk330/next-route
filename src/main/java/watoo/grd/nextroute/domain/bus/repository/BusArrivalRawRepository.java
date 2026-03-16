package watoo.grd.nextroute.domain.bus.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.bus.entity.BusArrivalRaw;

import java.time.LocalDateTime;
import java.util.List;

public interface BusArrivalRawRepository extends JpaRepository<BusArrivalRaw, Long> {

	List<BusArrivalRaw> findByRouteIdAndCollectedAtBetween(
			String routeId, LocalDateTime from, LocalDateTime to);
}
