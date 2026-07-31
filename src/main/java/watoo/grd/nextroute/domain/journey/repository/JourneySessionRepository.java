package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;

import java.util.Optional;
import java.util.UUID;

/**
 * 여정 조회.
 *
 * <p>이 저장소는 지운 표시가 된 행을 자동으로 걸러 주는 장치를 쓰지 않는다. 쿼리마다
 * {@code DeletedAtIsNull} 을 직접 붙이는 관례이므로 처음부터 조건을 포함해 만든다.
 */
public interface JourneySessionRepository extends JpaRepository<JourneySession, UUID> {

    /** 진행 중인 여정 찾기. {@code uq_journey_active_per_user} 덕분에 최대 한 건이다. */
    Optional<JourneySession> findByUserIdAndStatusAndDeletedAtIsNull(
            Long userId, JourneyStatus status);

    /** 남의 여정을 못 보게 소유자까지 조건에 넣는다. 조회 후 비교로 미루지 않는다. */
    Optional<JourneySession> findByIdAndUserIdAndDeletedAtIsNull(UUID id, Long userId);
}
