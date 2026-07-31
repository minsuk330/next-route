package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;

import java.util.Collection;
import java.util.List;

public interface JourneyLegRouteOptionRepository
        extends JpaRepository<JourneyLegRouteOption, Long> {

    /**
     * 여러 단계의 노선 후보를 한 번에.
     *
     * <p>단계마다 따로 조회하면 단계 수만큼 쿼리가 나간다. 단계를 한 번, 후보를 한 번 읽고
     * 메모리에서 조립하는 것이 이 표의 사용 방식이다.
     */
    List<JourneyLegRouteOption> findByJourneyLegIdInAndDeletedAtIsNull(
            Collection<Long> journeyLegIds);
}
