package watoo.grd.nextroute.domain.bus.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.bus.entity.BusPositionRaw;

import java.time.LocalDateTime;
import java.util.List;

public interface BusPositionRawRepository extends JpaRepository<BusPositionRaw, Long> {

	List<BusPositionRaw> findByRouteIdAndCollectedAtBetween(
			String routeId, LocalDateTime from, LocalDateTime to);
}
