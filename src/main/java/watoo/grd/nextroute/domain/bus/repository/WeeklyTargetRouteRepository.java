package watoo.grd.nextroute.domain.bus.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.bus.entity.WeeklyTargetRoute;

import java.util.List;

public interface WeeklyTargetRouteRepository extends JpaRepository<WeeklyTargetRoute, Long> {

    List<WeeklyTargetRoute> findByActiveTrueAndDeletedAtIsNull();
}
