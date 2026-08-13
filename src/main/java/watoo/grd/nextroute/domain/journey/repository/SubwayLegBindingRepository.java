package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;

import java.util.List;
import java.util.Optional;

public interface SubwayLegBindingRepository extends JpaRepository<SubwayLegBinding, Long> {

    Optional<SubwayLegBinding> findByJourneyLegId(Long journeyLegId);

    /**
     * 여러 단계의 고정을 한 번에.
     *
     * <p>여정 스냅샷을 조립할 때 쓴다. 단계마다 따로 물으면 지연 로딩이 단계 수만큼 질의를
     * 만든다 — 조립 루프 안에서는 반드시 이쪽을 쓴다.
     */
    List<SubwayLegBinding> findByJourneyLegIdIn(List<Long> journeyLegIds);
}
