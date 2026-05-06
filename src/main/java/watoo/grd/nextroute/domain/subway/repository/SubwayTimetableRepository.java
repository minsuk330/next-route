package watoo.grd.nextroute.domain.subway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.subway.entity.SubwayTimetable;

import java.util.List;

public interface SubwayTimetableRepository extends JpaRepository<SubwayTimetable, Long> {

	List<SubwayTimetable> findByTagoStationIdAndDayTypeAndDirection(
			String tagoStationId, String dayType, String direction);

	@Query("SELECT DISTINCT t.lineId as lineId, t.tagoStationId as tagoStationId, t.direction as direction " +
		   "FROM SubwayTimetable t WHERE t.dayType = :dayType AND t.lineId IS NOT NULL AND t.tagoStationId IS NOT NULL AND t.direction IS NOT NULL")
	List<TimetableCoverageProjection> findDistinctCoverage(@Param("dayType") String dayType);

	interface TimetableCoverageProjection {
		String getLineId();
		String getTagoStationId();
		String getDirection();
	}
}
