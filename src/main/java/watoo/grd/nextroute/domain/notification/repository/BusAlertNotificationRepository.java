package watoo.grd.nextroute.domain.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.notification.entity.BusAlertNotification;

import java.time.LocalDateTime;
import java.util.List;

public interface BusAlertNotificationRepository extends JpaRepository<BusAlertNotification, Long> {

    /**
     * enqueue: 구독 원자 claim(PENDING→PROCESSING) + outbox INSERT 를 단일 SQL 로 묶는다.
     * 구독이 PENDING 이 아니면 CTE 가 비어 INSERT 도 없음(빈 결과). unique 충돌은 ON CONFLICT DO NOTHING 으로
     * SQL 내에서 흡수(예외 없음 → rollback-only 함정 회피). 반환 = 생성된 outbox id(성공 시 1건, 아니면 0건).
     * <p>non-@Modifying(RETURNING), 반드시 @Transactional 안에서 호출.
     */
    @Query(value = """
            WITH claimed AS (
                UPDATE bus_arrival_alert a
                SET status = 'PROCESSING', processing_started_at = :now,
                    last_attempt_at = :now, attempt_count = a.attempt_count + 1
                WHERE a.id = :alertId AND a.status = 'PENDING' AND a.deleted_at IS NULL
                RETURNING a.id)
            INSERT INTO bus_alert_notification
                (alert_id, dedup_key, channel, event_type, status, scheduled_at, payload,
                 attempt_count, requeue_count, created_at, updated_at)
            SELECT c.id, :dedupKey, :channel, :eventType, 'PENDING', :now, :payload,
                   0, 0, :now, :now
            FROM claimed c
            ON CONFLICT (dedup_key) DO NOTHING
            RETURNING id
            """, nativeQuery = true)
    List<Long> enqueue(@Param("alertId") Long alertId, @Param("dedupKey") String dedupKey,
                       @Param("channel") String channel, @Param("eventType") String eventType,
                       @Param("payload") String payload, @Param("now") LocalDateTime now);

    /**
     * relay 배치 claim: PENDING → PROCESSING(SKIP LOCKED). 부모 구독이 PROCESSING(인계됨)인 것만.
     * FOR UPDATE OF o 로 outbox 행만 잠금. non-@Modifying(RETURNING), @Transactional 필수.
     */
    @Query(value = """
            WITH picked AS (
                SELECT o.id FROM bus_alert_notification o
                WHERE o.status = 'PENDING' AND o.scheduled_at <= :now AND o.deleted_at IS NULL
                  AND EXISTS (SELECT 1 FROM bus_arrival_alert a
                              WHERE a.id = o.alert_id AND a.status = 'PROCESSING' AND a.deleted_at IS NULL)
                ORDER BY o.scheduled_at
                LIMIT :batchSize
                FOR UPDATE OF o SKIP LOCKED)
            UPDATE bus_alert_notification n
            SET status = 'PROCESSING', processing_started_at = :now,
                attempt_count = n.attempt_count + 1, updated_at = :now
            FROM picked
            WHERE n.id = picked.id
            RETURNING n.id
            """, nativeQuery = true)
    List<Long> claimBatchForProcessing(@Param("now") LocalDateTime now, @Param("batchSize") int batchSize);

    /** 고착 PROCESSING reclaim → PENDING(scheduledAt=now). crash 소유권 회수. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update BusAlertNotification n
            set n.status = watoo.grd.nextroute.domain.notification.entity.BusNotificationStatus.PENDING,
                n.scheduledAt = :now
            where n.status = watoo.grd.nextroute.domain.notification.entity.BusNotificationStatus.PROCESSING
              and n.processingStartedAt < :staleBefore
            """)
    int reclaimStale(@Param("staleBefore") LocalDateTime staleBefore, @Param("now") LocalDateTime now);

    List<BusAlertNotification> findByStatusOrderByCreatedAtDesc(
            watoo.grd.nextroute.domain.notification.entity.BusNotificationStatus status);
}
