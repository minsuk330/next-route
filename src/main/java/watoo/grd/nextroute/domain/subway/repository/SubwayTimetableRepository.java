package watoo.grd.nextroute.domain.subway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.subway.entity.SubwayTimetable;

import java.util.List;

public interface SubwayTimetableRepository extends JpaRepository<SubwayTimetable, Long> {

	List<SubwayTimetable> findByTagoStationIdAndDayTypeAndDirection(
			String tagoStationId, String dayType, String direction);
}
