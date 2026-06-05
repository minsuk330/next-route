package watoo.grd.nextroute.domain.bus.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.bus.entity.BusArrivalCandidateRaw;

import java.util.List;

public interface BusArrivalCandidateRawRepository extends JpaRepository<BusArrivalCandidateRaw, Long> {

	@Query("select c.lifecycleId from BusArrivalCandidateRaw c where c.lifecycleId in :lifecycleIds")
	List<String> findExistingLifecycleIds(@Param("lifecycleIds") List<String> lifecycleIds);
}
