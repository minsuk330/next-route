package watoo.grd.nextroute.domain.route.favorite.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.route.favorite.entity.FavoriteRoute;
import watoo.grd.nextroute.domain.user.entity.User;

import java.util.List;
import java.util.Optional;

public interface FavoriteRouteRepository extends JpaRepository<FavoriteRoute, Long> {

    List<FavoriteRoute> findByUserAndDeletedAtIsNull(User user);

    Optional<FavoriteRoute> findByIdAndUserAndDeletedAtIsNull(Long id, User user);
}
