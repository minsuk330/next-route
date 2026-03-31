package watoo.grd.nextroute.domain.subway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;

import java.time.LocalDateTime;
import java.util.List;

public interface SubwayArrivalRawRepository extends JpaRepository<SubwayArrivalRaw, Long> {

	List<SubwayArrivalRaw> findByStationIdAndCollectedAtBetween(
			String stationId, LocalDateTime from, LocalDateTime to);

	List<SubwayArrivalRaw> findByStationIdAndCollectedAtAfter(
			String stationId, LocalDateTime from);
}
