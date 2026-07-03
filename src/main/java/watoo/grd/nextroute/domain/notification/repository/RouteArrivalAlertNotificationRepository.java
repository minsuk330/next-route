package watoo.grd.nextroute.domain.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlertNotification;

import java.util.List;

public interface RouteArrivalAlertNotificationRepository
        extends JpaRepository<RouteArrivalAlertNotification, Long> {

    List<RouteArrivalAlertNotification> findByAlertId(Long alertId);

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
}
