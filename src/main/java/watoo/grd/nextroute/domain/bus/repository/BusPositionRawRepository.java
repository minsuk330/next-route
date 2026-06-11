package watoo.grd.nextroute.domain.bus.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.application.bus.dto.BusPositionLabelRow;
import watoo.grd.nextroute.domain.bus.entity.BusPositionRaw;

import java.time.LocalDateTime;
import java.util.List;

public interface BusPositionRawRepository extends JpaRepository<BusPositionRaw, Long> {

	List<BusPositionRaw> findByRouteIdAndCollectedAtBetween(
			String routeId, LocalDateTime from, LocalDateTime to);

	/**
	 * 라벨 배치: route별 정차(stop_flag=1) position projection (8컬럼).
	 * 전체 행 로드 후 자바 필터 대신 stop_flag/is_run_yn을 DB로 내려 결과셋을 정차분으로 축소.
	 */
	@Query("select new watoo.grd.nextroute.application.bus.dto.BusPositionLabelRow("
			+ "p.id, p.vehicleId, p.plainNo, p.sectionId, p.sectionOrder, p.stopFlag, p.dataTm, p.collectedAt) "
			+ "from BusPositionRaw p "
			+ "where p.routeId = :routeId and p.collectedAt between :from and :to "
			+ "and p.stopFlag = '1' and p.isRunYn = '1'")
	List<BusPositionLabelRow> findLabelRowsByRouteIdAndCollectedAtBetween(
			@Param("routeId") String routeId,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to);
}
