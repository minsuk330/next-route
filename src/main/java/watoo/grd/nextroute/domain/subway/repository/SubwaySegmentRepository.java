package watoo.grd.nextroute.domain.subway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.subway.entity.SubwaySegment;

import java.util.List;

public interface SubwaySegmentRepository extends JpaRepository<SubwaySegment, Long> {

	List<SubwaySegment> findByLineIdOrderBySeq(String lineId);
}
