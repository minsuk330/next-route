package watoo.grd.nextroute.domain.journey.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;

import java.util.Optional;

/**
 * 여정 조회.
 *
 * <p>이 저장소는 지운 표시가 된 행을 자동으로 걸러 주는 장치를 쓰지 않는다. 쿼리마다
 * {@code DeletedAtIsNull} 을 직접 붙이는 관례이므로 처음부터 조건을 포함해 만든다.
 */
public interface JourneySessionRepository extends JpaRepository<JourneySession, Long> {

    /** 진행 중인 여정 찾기. {@code uq_journey_active_per_user} 덕분에 최대 한 건이다. */
    Optional<JourneySession> findByUserIdAndStatusAndDeletedAtIsNull(
            Long userId, JourneyStatus status);

    /** 남의 여정을 못 보게 소유자까지 조건에 넣는다. 조회 후 비교로 미루지 않는다. */
    Optional<JourneySession> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    /**
     * 여정 행을 잠그고 읽는다. 같은 여정에 온 명령을 한 줄로 세운다.
     *
     * <p>명령 처리는 "지금 상태를 읽고 → 바뀔 내용을 정하고 → 쓴다" 세 걸음이다. 사용자가 버튼을
     * 두 번 누르거나 앱이 재전송하면 두 요청이 같은 상태를 읽고 둘 다 반영하려 든다. 잠금이
     * 그 사이를 막는다.
     *
     * <p>잠그는 대상이 여정 한 행이라 다른 사람의 여정은 물론, <b>같은 사람의 다른 여정</b>도
     * 방해받지 않는다. 반드시 트랜잭션 안에서 호출한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select j from JourneySession j
            where j.id = :id and j.userId = :userId and j.deletedAt is null
            """)
    Optional<JourneySession> findByIdAndUserIdForUpdate(@Param("id") Long id,
                                                        @Param("userId") Long userId);
}
