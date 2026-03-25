package watoo.grd.nextroute.domain.subway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.subway.entity.SubwayStationTago;

import java.util.List;
import java.util.Optional;

public interface SubwayStationTagoRepository extends JpaRepository<SubwayStationTago, Long> {

	boolean existsByTagoStationId(String tagoStationId);

	List<SubwayStationTago> findByStationIdIsNotNull();

	Optional<SubwayStationTago> findByTagoStationId(String tagoStationId);
}
