package watoo.grd.nextroute.domain.subway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEventMatchIssue;

import java.time.LocalDate;

public interface SubwayArrivalEventMatchIssueRepository extends JpaRepository<SubwayArrivalEventMatchIssue, Long> {

	@Modifying
	@Query("DELETE FROM SubwayArrivalEventMatchIssue e WHERE e.serviceDate = :serviceDate")
	int deleteByServiceDate(@Param("serviceDate") LocalDate serviceDate);

	long countByServiceDate(LocalDate serviceDate);
}
