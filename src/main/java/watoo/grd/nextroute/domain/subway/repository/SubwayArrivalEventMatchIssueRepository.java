package watoo.grd.nextroute.domain.subway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEventMatchIssue;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface SubwayArrivalEventMatchIssueRepository extends JpaRepository<SubwayArrivalEventMatchIssue, Long> {

	@Modifying
	@Query("DELETE FROM SubwayArrivalEventMatchIssue e WHERE e.serviceDate = :serviceDate")
	int deleteByServiceDate(@Param("serviceDate") LocalDate serviceDate);

	long countByServiceDate(LocalDate serviceDate);

	// Phase C: 보완 대상 NO_RAW_EVENT 슬롯 조회 (serviceDate + 대상 라인)
	List<SubwayArrivalEventMatchIssue> findByServiceDateAndIssueTypeAndLineIdIn(
			LocalDate serviceDate, String issueType, Collection<String> lineIds);
}
