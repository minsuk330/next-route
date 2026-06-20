package watoo.grd.nextroute.domain.bus.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;

import java.util.Collection;
import java.util.List;

public interface BusRouteStopRepository extends JpaRepository<BusRouteStop, Long> {

	List<BusRouteStop> findByRouteIdOrderBySeq(String routeId);

	List<BusRouteStop> findByRouteIdAndStopId(String routeId, String stopId);

	boolean existsByRouteIdAndStopIdAndSeq(String routeId, String stopId, Integer seq);

	void deleteByRouteId(String routeId);

	/** 주어진 정류장들 중 지원 노선을 경유하는 정류장 id만 반환 (배지 판정용, 전체 fetch 회피). */
	@Query("""
			SELECT DISTINCT brs.stopId FROM BusRouteStop brs
			WHERE brs.stopId IN :stopIds AND brs.routeId IN :routeIds
			""")
	List<String> findSupportedStopIds(@Param("stopIds") Collection<String> stopIds,
									   @Param("routeIds") Collection<String> routeIds);

	/** 정류장 경유 노선 목록 (정류장→노선 역조회, route 메타 조인). */
	@Query("""
			SELECT brs.routeId AS routeId, br.routeName AS routeName, brs.direction AS direction,
			       br.routeType AS routeType, br.startStation AS startStation, br.endStation AS endStation
			FROM BusRouteStop brs JOIN brs.busRoute br
			WHERE brs.stopId = :stopId
			ORDER BY br.routeName
			""")
	List<StopRouteProjection> findRoutesByStopId(@Param("stopId") String stopId);

	/** 노선 경유 정류장 목록 (seq 순, stop 좌표 조인). */
	@Query("""
			SELECT brs.seq AS seq, brs.stopId AS stopId, bs.stopName AS stopName,
			       bs.latitude AS latitude, bs.longitude AS longitude, brs.direction AS direction
			FROM BusRouteStop brs JOIN brs.busStop bs
			WHERE brs.routeId = :routeId
			ORDER BY brs.seq
			""")
	List<RouteStopProjection> findStopsByRouteId(@Param("routeId") String routeId);
}
