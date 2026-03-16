package watoo.grd.nextroute.domain.subway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;

import java.util.List;
import java.util.Optional;

public interface SubwayStationRepository extends JpaRepository<SubwayStation, Long> {

	Optional<SubwayStation> findByStationId(String stationId);

	List<SubwayStation> findByLineId(String lineId);

	boolean existsByStationId(String stationId);
}
