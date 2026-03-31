package watoo.grd.nextroute.domain.bus.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.bus.entity.BusArrivalRaw;

import java.time.LocalDateTime;
import java.util.List;

public interface BusArrivalRawRepository extends JpaRepository<BusArrivalRaw, Long> {

	List<BusArrivalRaw> findByRouteIdAndCollectedAtBetween(
			String routeId, LocalDateTime from, LocalDateTime to);

	// 정류소별 노선 최신 1건씩 (DISTINCT ON은 PostgreSQL 전용)
	@Query(value = """
			SELECT DISTINCT ON (route_id) *
			FROM bus_arrival_raw
			WHERE stop_id = :stopId
			  AND collected_at >= :from
			ORDER BY route_id, collected_at DESC
			""", nativeQuery = true)
	List<BusArrivalRaw> findLatestByStopId(
			@Param("stopId") String stopId,
			@Param("from") LocalDateTime from);
}
