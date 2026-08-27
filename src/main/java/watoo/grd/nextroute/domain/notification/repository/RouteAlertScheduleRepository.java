package watoo.grd.nextroute.domain.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * 탈퇴: 그 사용자의 알림 스케줄을 물리 삭제한다. 남으면 spawner 가 지운 계정의 알림을 만든다.
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from RouteAlertSchedule s where s.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
