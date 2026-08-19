package watoo.grd.nextroute.domain.bus.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.bus.entity.BusStop;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface BusStopRepository extends JpaRepository<BusStop, Long> {

	Optional<BusStop> findByStopId(String stopId);

	Optional<BusStop> findByArsId(String arsId);

	boolean existsByStopId(String stopId);

	List<BusStop> findByStopIdIn(Collection<String> stopIds);

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

	/**
	 * 지도 스냅샷 전량 조회.
	 *
	 * <p>{@code findNearby} 와 달리 {@code geom} 을 보지 않는다 — 스냅샷은 좌표만 쓰므로 판단 기준도
	 * 좌표 컬럼이다. 대신 소프트삭제는 제외한다.
	 *
	 * <p>{@code ORDER BY stop_id} 는 취향이 아니라 계약이다. 같은 데이터면 응답 바이트가 같아야
	 * ETag 가 안정적이고 클라 디핑이 가능하다.
	 */
	@Query(value = """
			SELECT stop_id AS stopId, stop_name AS stopName, ars_id AS arsId,
			       latitude AS latitude, longitude AS longitude
			FROM bus_stop
			WHERE deleted_at IS NULL
			  AND latitude IS NOT NULL
			  AND longitude IS NOT NULL
			ORDER BY stop_id
			""", nativeQuery = true)
	List<BusStopSnapshotProjection> findAllForSnapshot();

	/**
	 * 스냅샷 버전 프로브. 본문을 만들지 않고 304 를 판단하기 위한 집계 한 방.
	 *
	 * <p>{@code findAllForSnapshot} 과 WHERE 절이 같아야 한다 — 다르면 버전은 안 바뀌는데 본문은
	 * 바뀌는 상태가 생긴다.
	 */
	@Query(value = """
			SELECT count(*) AS stopCount,
			       coalesce(extract(epoch FROM max(updated_at))::bigint, 0) AS maxUpdatedEpochSec
			FROM bus_stop
			WHERE deleted_at IS NULL
			  AND latitude IS NOT NULL
			  AND longitude IS NOT NULL
			""", nativeQuery = true)
	BusStopSnapshotVersionProjection probeSnapshotVersion();

	@Modifying
	@Query(value = "UPDATE bus_stop SET geom = ST_MakePoint(longitude, latitude)::geography WHERE longitude IS NOT NULL AND latitude IS NOT NULL", nativeQuery = true)
	void refreshGeom();
}
