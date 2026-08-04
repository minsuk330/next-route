package watoo.grd.nextroute.domain.journey.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;

import java.time.Instant;
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

    /**
     * 평가 번호만 하나 올린다. 진행 중인 여정에만 적용한다.
     *
     * <p>{@code commandVersion} 은 건드리지 않는다. progress 는 명령이 아니라 관측이라서, 이것으로
     * 낙관적 락이 깨지면 앱이 보낸 정상 명령이 충돌로 거절된다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update JourneySession j
               set j.evaluationVersion = j.evaluationVersion + 1
             where j.id = :id and j.status = watoo.grd.nextroute.domain.journey.entity.JourneyStatus.ACTIVE
               and j.deletedAt is null
            """)
    int bumpEvaluationVersion(@Param("id") Long id);

    /**
     * lease 를 연장한다. 마지막 신호가 {@code coalesceBefore} 보다 오래됐을 때만 쓴다.
     *
     * <p>progress 는 15초(하차 임박 5초)마다 들어오는데 그때마다 이 행을 쓰면 여정 하나가
     * 계속 갱신되는 hot row 가 된다. 실제 쓰기를 여정별 최대 60초에 한 번으로 합치는 이유다.
     * lease 자체는 3분이라 60초 간격으로 늘려도 만료되지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update JourneySession j
               set j.lastHeartbeatAt = :now, j.guidanceLeaseUntil = :leaseUntil
             where j.id = :id and j.status = watoo.grd.nextroute.domain.journey.entity.JourneyStatus.ACTIVE
               and j.deletedAt is null
               and j.lastHeartbeatAt < :coalesceBefore
            """)
    int extendLeaseIfStale(@Param("id") Long id,
                           @Param("now") Instant now,
                           @Param("leaseUntil") Instant leaseUntil,
                           @Param("coalesceBefore") Instant coalesceBefore);
}
