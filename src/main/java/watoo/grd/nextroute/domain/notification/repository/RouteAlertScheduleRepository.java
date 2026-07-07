package watoo.grd.nextroute.domain.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertSchedule;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertScheduleStatus;
import watoo.grd.nextroute.domain.user.entity.User;

import java.util.List;
import java.util.Optional;

public interface RouteAlertScheduleRepository extends JpaRepository<RouteAlertSchedule, Long> {

    List<RouteAlertSchedule> findByUserAndDeletedAtIsNull(User user);

    List<RouteAlertSchedule> findByUserAndStatusAndDeletedAtIsNull(User user, RouteAlertScheduleStatus status);

    Optional<RouteAlertSchedule> findByIdAndUserAndDeletedAtIsNull(Long id, User user);

    /** spawner 스캔: 운영 중 구독. idx_route_alert_schedule_status 사용. */
    List<RouteAlertSchedule> findByStatusAndDeletedAtIsNull(RouteAlertScheduleStatus status);
}
