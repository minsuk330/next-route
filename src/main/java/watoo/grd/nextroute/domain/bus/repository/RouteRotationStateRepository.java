package watoo.grd.nextroute.domain.bus.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.bus.entity.RouteRotationState;

import java.util.Optional;

public interface RouteRotationStateRepository extends JpaRepository<RouteRotationState, Long> {

    Optional<RouteRotationState> findFirstByDeletedAtIsNullOrderByIdAsc();
}
