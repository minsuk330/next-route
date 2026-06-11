package watoo.grd.nextroute.domain.bus.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.application.bus.dto.BusArrivalCandidateLabelRow;
import watoo.grd.nextroute.domain.bus.entity.BusArrivalCandidateRaw;

import java.time.LocalDateTime;
import java.util.List;

public interface BusArrivalCandidateRawRepository extends JpaRepository<BusArrivalCandidateRaw, Long> {

	@Query("select c.lifecycleId from BusArrivalCandidateRaw c where c.lifecycleId in :lifecycleIds")
	List<String> findExistingLifecycleIds(@Param("lifecycleIds") List<String> lifecycleIds);

	/** 라벨 배치: service_date 범위에 finalize된 candidate의 distinct route_id (route 단위 분할 로드용). */
	@Query("select distinct c.routeId from BusArrivalCandidateRaw c "
			+ "where c.finalizedAt between :from and :to and c.routeId is not null")
	List<String> findDistinctRouteIdsByFinalizedAtBetween(@Param("from") LocalDateTime from,
														  @Param("to") LocalDateTime to);

	/**
	 * 라벨 배치: route별 candidate projection (필터 DB 이관 + 12컬럼만).
	 * 전체 50만건 한방 로드 대신 route별로 쪼개 동시 상주를 노선당 ~1.6만으로 바운드한다.
	 */
	@Query("select new watoo.grd.nextroute.application.bus.dto.BusArrivalCandidateLabelRow("
			+ "c.id, c.lifecycleId, c.routeId, c.vehicleId, c.vehicleIdentity, c.vehicleIdentityType, "
			+ "c.stopId, c.seq, c.arrivalOrder, c.arrivalMsg, c.dataTimestamp, c.predictTime) "
			+ "from BusArrivalCandidateRaw c "
			+ "where c.routeId = :routeId and c.finalizedAt between :from and :to "
			+ "and c.lifecycleId is not null "
			+ "and c.arrivalOrder = 1 "
			+ "and (c.arrivalMsg is null or (c.arrivalMsg not like '%출발대기%' and c.arrivalMsg not like '%회차대기%')) "
			+ "and c.vehicleIdentity is not null and trim(c.vehicleIdentity) <> ''")
	List<BusArrivalCandidateLabelRow> findLabelRowsByRouteIdAndFinalizedAtBetween(
			@Param("routeId") String routeId,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to);
}
