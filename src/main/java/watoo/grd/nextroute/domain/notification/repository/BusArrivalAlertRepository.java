package watoo.grd.nextroute.domain.notification.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.notification.entity.AlertStatus;
import watoo.grd.nextroute.domain.notification.entity.BusArrivalAlert;
import watoo.grd.nextroute.domain.user.entity.User;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BusArrivalAlertRepository extends JpaRepository<BusArrivalAlert, Long> {

    /** 디스패치 대상: 활성 PENDING + 만료 전 + 백오프 경과. starvation 방지 위해 조건을 쿼리에서 거른다. */
    @Query("""
            select a from BusArrivalAlert a
            join fetch a.user
            where a.status = watoo.grd.nextroute.domain.notification.entity.AlertStatus.PENDING
              and a.deletedAt is null and a.expiresAt > :now
              and (a.lastAttemptAt is null or a.lastAttemptAt < :retryBefore)
            order by a.lastAttemptAt asc nulls first, a.createdAt asc
            """)
    List<BusArrivalAlert> findDispatchable(@Param("now") LocalDateTime now,
                                           @Param("retryBefore") LocalDateTime retryBefore,
                                           Pageable pageable);

    /** 멱등 선검사: 동일 (user, stop, route, ord) 활성 구독. */
    Optional<BusArrivalAlert> findFirstByUserAndStopIdAndRouteIdAndOrdAndStatusInAndDeletedAtIsNull(
            User user, String stopId, String routeId, Integer ord, Collection<AlertStatus> statuses);

    List<BusArrivalAlert> findByUserAndDeletedAtIsNull(User user);

    Optional<BusArrivalAlert> findByIdAndUserAndDeletedAtIsNull(Long id, User user);

    /**
     * 원자 claim: PENDING → PROCESSING(단일 승자). 영향행수 1=claim, 0=경쟁서 짐/이미 비-PENDING.
     * read-then-write 를 조건부 UPDATE 로 대체해 동시 worker/다중 인스턴스의 중복 발송을 차단한다
     * (Postgres 행 락으로 동시 UPDATE 중 하나만 status=PENDING 조건에 매치). markProcessing 과 동일 필드 전이.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update BusArrivalAlert a
            set a.status = watoo.grd.nextroute.domain.notification.entity.AlertStatus.PROCESSING,
                a.processingStartedAt = :now, a.lastAttemptAt = :now, a.attemptCount = a.attemptCount + 1
            where a.id = :id
              and a.status = watoo.grd.nextroute.domain.notification.entity.AlertStatus.PENDING
              and a.deletedAt is null
            """)
    int claimForProcessing(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** bulk 만료: PENDING만 대상(발송중 PROCESSING은 reclaim으로만 복귀). */
    @Modifying(clearAutomatically = true)
    @Query("""
            update BusArrivalAlert a
            set a.status = watoo.grd.nextroute.domain.notification.entity.AlertStatus.EXPIRED
            where a.status = watoo.grd.nextroute.domain.notification.entity.AlertStatus.PENDING
              and a.expiresAt < :now and a.deletedAt is null
            """)
    int expirePendingOverdue(@Param("now") LocalDateTime now);

    /** bulk reclaim: 고착 PROCESSING → PENDING. */
    @Modifying(clearAutomatically = true)
    @Query("""
            update BusArrivalAlert a
            set a.status = watoo.grd.nextroute.domain.notification.entity.AlertStatus.PENDING,
                a.lastFailureReason = 'reclaimed'
            where a.status = watoo.grd.nextroute.domain.notification.entity.AlertStatus.PROCESSING
              and a.processingStartedAt < :threshold and a.deletedAt is null
            """)
    int reclaimStale(@Param("threshold") LocalDateTime threshold);
}
