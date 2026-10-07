package watoo.grd.nextroute.domain.subway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.repository.NearbySubwayStationProjection;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubwayStationRepository extends JpaRepository<SubwayStation, Long> {

	Optional<SubwayStation> findByStationId(String stationId);

	Optional<SubwayStation> findByStationIdAndLineId(String stationId, String lineId);

	List<SubwayStation> findByLineId(String lineId);

	List<SubwayStation> findByLineIdAndTagoStationId(String lineId, String tagoStationId);

	List<SubwayStation> findByLineIdIn(Collection<String> lineIds);

	List<SubwayStation> findByTagoStationIdIsNotNull();

	boolean existsByStationId(String stationId);

	List<SubwayStation> findByLatitudeIsNull();

	@Query("SELECT s FROM SubwayStation s WHERE s.lineName = :lineName AND (s.stationName = :name OR s.stationName LIKE CONCAT(:name, '(%)'))")
	SubwayStation findByStationNameLikeAndLineName(@Param("name") String name, @Param("lineName") String lineName);

	@Query(value = """
			SELECT statn_id AS station_id, statn_nm AS station_name, line_id,
			       search_line_name AS line_name, latitude, longitude,
			       ST_Distance(geom, ST_MakePoint(:lng, :lat)::geography) AS dist_meters
			FROM subway_station
			WHERE geom IS NOT NULL
			  AND ST_DWithin(geom, ST_MakePoint(:lng, :lat)::geography, :radiusMeters)
			ORDER BY dist_meters
			LIMIT :lim
			""", nativeQuery = true)
	List<NearbySubwayStationProjection> findNearby(
			@Param("lat") double lat,
			@Param("lng") double lng,
			@Param("radiusMeters") double radiusMeters,
			@Param("lim") int lim);

	/**
	 * {@link #findNearby} 와 같되 한 노선만 본다.
	 *
	 * <p>노선 조건을 거리 정렬·LIMIT 앞에 둬야 한다. 노선이 많은 환승역에서는 다른 노선 행이
	 * LIMIT 을 다 채워 요청 노선이 반경 안에 있어도 잘려 나간다.
	 */
	@Query(value = """
			SELECT statn_id AS station_id, statn_nm AS station_name, line_id,
			       search_line_name AS line_name, latitude, longitude,
			       ST_Distance(geom, ST_MakePoint(:lng, :lat)::geography) AS dist_meters
			FROM subway_station
			WHERE geom IS NOT NULL
			  AND line_id = :lineId
			  AND ST_DWithin(geom, ST_MakePoint(:lng, :lat)::geography, :radiusMeters)
			ORDER BY dist_meters
			LIMIT :lim
			""", nativeQuery = true)
	List<NearbySubwayStationProjection> findNearbyOnLine(
			@Param("lat") double lat,
			@Param("lng") double lng,
			@Param("radiusMeters") double radiusMeters,
			@Param("lineId") String lineId,
			@Param("lim") int lim);

	@Modifying
	@Query(value = "UPDATE subway_station SET geom = ST_MakePoint(longitude, latitude)::geography WHERE geom IS NULL AND longitude IS NOT NULL AND latitude IS NOT NULL", nativeQuery = true)
	void backfillGeom();
}
