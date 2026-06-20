package watoo.grd.nextroute.domain.bus.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.bus.entity.BusStop;

import java.util.List;
import java.util.Optional;

public interface BusStopRepository extends JpaRepository<BusStop, Long> {

	Optional<BusStop> findByStopId(String stopId);

	Optional<BusStop> findByArsId(String arsId);

	boolean existsByStopId(String stopId);

	/** 정류장명 prefix 자동완성 (와일드카드 이스케이프는 Spring Data가 처리). */
	List<BusStop> findTop20ByStopNameStartingWithOrderByStopName(String stopName);

	@Query(value = """
			SELECT stop_id, stop_name, ars_id, latitude, longitude,
			       ST_Distance(geom, ST_MakePoint(:lng, :lat)::geography) AS dist_meters
			FROM bus_stop
			WHERE geom IS NOT NULL
			  AND ST_DWithin(geom, ST_MakePoint(:lng, :lat)::geography, :radiusMeters)
			ORDER BY dist_meters
			LIMIT :lim
			""", nativeQuery = true)
	List<NearbyBusStopProjection> findNearby(
			@Param("lat") double lat,
			@Param("lng") double lng,
			@Param("radiusMeters") double radiusMeters,
			@Param("lim") int lim);

	@Modifying
	@Query(value = "UPDATE bus_stop SET geom = ST_MakePoint(longitude, latitude)::geography WHERE geom IS NULL AND longitude IS NOT NULL AND latitude IS NOT NULL", nativeQuery = true)
	void backfillGeom();
}
