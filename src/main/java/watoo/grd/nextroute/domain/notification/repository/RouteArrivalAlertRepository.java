package watoo.grd.nextroute.domain.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.user.entity.User;

import java.util.List;
import java.util.Optional;

public interface RouteArrivalAlertRepository extends JpaRepository<RouteArrivalAlert, Long> {

    List<RouteArrivalAlert> findByUserAndDeletedAtIsNull(User user);

    Optional<RouteArrivalAlert> findByIdAndUserAndDeletedAtIsNull(Long id, User user);
}
