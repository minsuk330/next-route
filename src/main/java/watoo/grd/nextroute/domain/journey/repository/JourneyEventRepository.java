package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.journey.entity.JourneyEvent;

import java.util.Optional;
import java.util.UUID;

public interface JourneyEventRepository extends JpaRepository<JourneyEvent, Long> {

    /**
     * 같은 명령이 이미 처리됐는지. 재전송이면 최초 결과를 그대로 돌려주기 위해 쓴다(Phase 2).
     *
     * <p>지운 표시를 조건에 넣지 않는다. 중복 판정의 기준은 {@code uq_journey_event_id} 이고
     * 이 인덱스에는 {@code deleted_at} 조건이 없다 — 조건을 붙이면 코드와 DB 의 판정이 갈린다.
     */
    Optional<JourneyEvent> findByEventId(UUID eventId);
}
