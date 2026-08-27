package watoo.grd.nextroute.domain.notification.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlertNotification;

import java.time.LocalDateTime;
import java.util.List;

public interface RouteArrivalAlertNotificationRepository
        extends JpaRepository<RouteArrivalAlertNotification, Long> {

    List<RouteArrivalAlertNotification> findByAlertId(Long alertId);

    /** 디스패치 대상: PENDING + 발송예정 도래. scheduledAt 은 백오프 재시도 시 미뤄진다. */
    @Query("""
            select n from RouteArrivalAlertNotification n
            where n.status = watoo.grd.nextroute.domain.notification.entity.RouteNotificationStatus.PENDING
              and n.scheduledAt <= :now
              and n.deletedAt is null
            order by n.scheduledAt
            """)
    List<RouteArrivalAlertNotification> findDispatchable(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * 원자 claim: PENDING → PROCESSING(단일 승자 보장). 갱신 행수 1=claim, 0=경쟁서 짐.
     * alert 가 비-ACTIVE 이거나 soft-delete(수정으로 SUPERSEDED/조기 DONE) 면 claim 하지 않는다(중복 발송 차단).
     * bulk update 는 join 불가라 subquery(alert PK)로 조건을 건다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RouteArrivalAlertNotification n
            set n.status = watoo.grd.nextroute.domain.notification.entity.RouteNotificationStatus.PROCESSING,
                n.processingStartedAt = :now,
                n.attemptCount = n.attemptCount + 1
            where n.id = :id
              and n.status = watoo.grd.nextroute.domain.notification.entity.RouteNotificationStatus.PENDING
              and exists (select 1 from RouteArrivalAlert a
                          where a.id = n.alert.id
                            and a.status = watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus.ACTIVE
                            and a.deletedAt is null)
            """)
    int claimForProcessing(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 고착 PROCESSING reclaim → PENDING(scheduledAt=now). */
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
     * 취소·만료 전파: 해당 alert 의 PENDING row 를 CANCELED 로 일괄 전이(§4.3).
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

    /**
     * 탈퇴: 그 사용자의 알림에 매달린 발송 이력을 물리 삭제한다. 부모 알림보다 먼저 부른다(FK 역순).
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            delete from RouteArrivalAlertNotification n
             where n.alert.id in (select a.id from RouteArrivalAlert a where a.user.id = :userId)
            """)
    void deleteAllByUserId(@Param("userId") Long userId);
}
