package watoo.grd.nextroute.domain.subway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEvent;

import java.time.LocalDate;
import java.util.List;

public interface SubwayArrivalEventRepository extends JpaRepository<SubwayArrivalEvent, Long> {

	// service_date 단위 삭제 (delete-and-recompute)
	@Modifying
	@Query("DELETE FROM SubwayArrivalEvent e WHERE e.serviceDate = :serviceDate")
	int deleteByServiceDate(@Param("serviceDate") LocalDate serviceDate);

	// 검증용 조회
	List<SubwayArrivalEvent> findByServiceDate(LocalDate serviceDate);

	// 중복 확인 (unique key 기준)
	long countByServiceDate(LocalDate serviceDate);
}
