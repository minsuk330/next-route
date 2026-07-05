package watoo.grd.nextroute.domain.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.user.entity.User;

import java.time.LocalDateTime;
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
}
