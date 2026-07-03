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
}
