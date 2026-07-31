package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;

import java.util.List;
import java.util.UUID;

public interface JourneyLegRepository extends JpaRepository<JourneyLeg, Long> {

    /** 한 개정본의 단계 전부를 순서대로. 화면과 상태 판단이 모두 이 순서를 전제한다. */
    List<JourneyLeg> findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
            UUID journeyId, int routeRevision);
}
