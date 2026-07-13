package watoo.grd.nextroute.domain.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlertNotification;
import watoo.grd.nextroute.domain.notification.entity.RouteNotificationStatus;

import java.time.LocalDateTime;
import java.util.List;

public interface RouteArrivalAlertNotificationRepository
        extends JpaRepository<RouteArrivalAlertNotification, Long> {

    List<RouteArrivalAlertNotification> findByAlertId(Long alertId);

    List<RouteArrivalAlertNotification> findByStatusOrderByCreatedAtDesc(RouteNotificationStatus status);

    /**
     * 배치 원자 claim: PENDING → PROCESSING 을 SKIP LOCKED 로 최대 batchSize 건 집는다.
     * CTE 가 잠근 행만 UPDATE 하므로 동시 dispatcher(다중 스레드/인스턴스)가 있어도 각 행의 단일 승자만 발송한다.
     * FOR UPDATE OF o 로 outbox 행만 잠금 — 부모 alert 행을 잠그면 checker 의 낙관락(@Version)과 경합한다.
     * <p>UPDATE...RETURNING 이라 non-@Modifying(결과셋 반환). 반드시 @Transactional 안에서 호출하고,
     * 이 트랜잭션에서 엔티티를 로드하지 않는다(id 만 다룬다).
     */
    @Query(value = """
            WITH picked AS (
                SELECT o.id FROM route_arrival_alert_notification o
                WHERE o.status = 'PENDING' AND o.scheduled_at <= :now AND o.deleted_at IS NULL
                  AND EXISTS (SELECT 1 FROM route_arrival_alert a
                              WHERE a.id = o.alert_id AND a.status = 'ACTIVE' AND a.deleted_at IS NULL)
                ORDER BY o.scheduled_at
                LIMIT :batchSize
                FOR UPDATE OF o SKIP LOCKED)
            UPDATE route_arrival_alert_notification n
            SET status = 'PROCESSING', processing_started_at = :now,
                attempt_count = n.attempt_count + 1, updated_at = :now
            FROM picked
            WHERE n.id = picked.id
            RETURNING n.id
            """, nativeQuery = true)
    List<Long> claimBatchForProcessing(@Param("now") LocalDateTime now, @Param("batchSize") int batchSize);

    /**
     * stale PENDING 정리: 부모가 비-ACTIVE/soft-delete/목표 과거이거나, DEPARTURE 인데 phase 가 이미 넘어간
     * outbox 행을 CANCELED 로 전이한다. claim 이 EXISTS(ACTIVE) 로 걸러내면 이런 행이 영구히 PENDING 으로 남으므로
     * claim 직전에 같은 트랜잭션에서 실행한다. (기존 per-row stale 가드 4조건을 SQL 로 이전.)
     */
    @Modifying
    @Query(value = """
            UPDATE route_arrival_alert_notification n
            SET status = 'CANCELED', updated_at = :now
            FROM route_arrival_alert a
            WHERE n.alert_id = a.id
              AND n.status = 'PENDING' AND n.scheduled_at <= :now AND n.deleted_at IS NULL
              AND (a.status <> 'ACTIVE' OR a.deleted_at IS NOT NULL OR a.target_arrival_at < :now
                   OR (n.type = 'DEPARTURE' AND a.phase <> 'WAITING_DEPARTURE'))
            """, nativeQuery = true)
    int cancelStaleDispatchable(@Param("now") LocalDateTime now);

    /** 고착 PROCESSING reclaim → PENDING(scheduledAt=now). SKIP LOCKED 는 락 해제로 소유권을 못 넘기므로 필수. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RouteArrivalAlertNotification n
            set n.status = watoo.grd.nextroute.domain.notification.entity.RouteNotificationStatus.PENDING,
                n.scheduledAt = :now
            where n.status = watoo.grd.nextroute.domain.notification.entity.RouteNotificationStatus.PROCESSING
              and n.processingStartedAt < :staleBefore
            """)
    int reclaimStale(@Param("staleBefore") LocalDateTime staleBefore, @Param("now") LocalDateTime now);

    /**
     * 취소·만료 전파: 해당 alert 의 PENDING row 를 CANCELED 로 일괄 전이(checker 경로).
     * flushAutomatically=true: 같은 트랜잭션에서 먼저 변경된 alert(status) dirty 상태를
     * bulk update 전에 flush 하여, clearAutomatically 로 컨텍스트가 비워질 때 유실되지 않게 한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RouteArrivalAlertNotification n
            set n.status = watoo.grd.nextroute.domain.notification.entity.RouteNotificationStatus.CANCELED
            where n.alert.id = :alertId
              and n.status = watoo.grd.nextroute.domain.notification.entity.RouteNotificationStatus.PENDING
            """)
    int cancelPendingByAlertId(@Param("alertId") Long alertId);
}
