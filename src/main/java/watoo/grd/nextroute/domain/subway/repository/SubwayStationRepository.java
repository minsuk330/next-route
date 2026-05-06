package watoo.grd.nextroute.domain.subway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.repository.NearbySubwayStationProjection;

import java.util.List;
import java.util.Optional;

public interface SubwayStationRepository extends JpaRepository<SubwayStation, Long> {

	Optional<SubwayStation> findByStationId(String stationId);

	List<SubwayStation> findByLineId(String lineId);

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

	@Modifying
	@Query(value = "UPDATE subway_station SET geom = ST_MakePoint(longitude, latitude)::geography WHERE geom IS NULL AND longitude IS NOT NULL AND latitude IS NOT NULL", nativeQuery = true)
	void backfillGeom();
}
