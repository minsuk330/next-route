package watoo.grd.nextroute.domain.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.user.entity.User;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RouteArrivalAlertRepository extends JpaRepository<RouteArrivalAlert, Long> {

    List<RouteArrivalAlert> findByUserAndDeletedAtIsNull(User user);

    Optional<RouteArrivalAlert> findByIdAndUserAndDeletedAtIsNull(Long id, User user);

    /**
     * 체크 스케줄러 스캔: 활성 + 다음 액션 도래. WAITING_DEPARTURE 는 next_action_at=NULL 이라 제외된다.
     * idx_route_alert_scan 사용.
     */
    List<RouteArrivalAlert> findByStatusAndNextActionAtLessThanEqualAndDeletedAtIsNull(
            RouteAlertStatus status, LocalDateTime nextActionAt);

    /**
     * 만료 스윕: 활성 + 만료 도래. WAITING_DEPARTURE(next_action_at=NULL, 발송/실패로 고착 가능)까지 포함해
     * 고아 ACTIVE 를 정리한다. idx_route_alert_status_expires 사용.
     */
    List<RouteArrivalAlert> findByStatusAndExpiresAtLessThanEqualAndDeletedAtIsNull(
            RouteAlertStatus status, LocalDateTime expiresAt);

    /** spawner in-flight 판정: schedule 에 진행 중(ACTIVE) occurrence 가 있는가. idx_route_alert_inflight 사용. */
    boolean existsByScheduleIdAndStatusAndDeletedAtIsNull(Long scheduleId, RouteAlertStatus status);

    /** 취소 전파: schedule 의 특정 status occurrence 목록(in-flight cancel 용). */
    List<RouteArrivalAlert> findByScheduleIdAndStatusAndDeletedAtIsNull(Long scheduleId, RouteAlertStatus status);

    /** 일회성 종결 판정: schedule 의 마지막 occurrence(최신 id). status 로 DONE/EXPIRED 구분. */
    Optional<RouteArrivalAlert> findTopByScheduleIdOrderByIdDesc(Long scheduleId);

    /** getActive batch: 여러 schedule 의 in-flight(ACTIVE) occurrence 를 한 번에(Q3 N+1 방지). */
    @Query("""
            select a from RouteArrivalAlert a
            where a.scheduleId in :scheduleIds
              and a.status = watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus.ACTIVE
              and a.deletedAt is null
            """)
    List<RouteArrivalAlert> findActiveOccurrencesByScheduleIds(@Param("scheduleIds") Collection<Long> scheduleIds);
}
