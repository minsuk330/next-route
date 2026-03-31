package watoo.grd.nextroute.domain.route.favorite.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.route.favorite.entity.FavoriteRoute;
import watoo.grd.nextroute.domain.route.favorite.repository.FavoriteRouteRepository;
import watoo.grd.nextroute.domain.user.entity.User;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FavoriteRouteService {

    private final FavoriteRouteRepository favoriteRouteRepository;

    @Transactional
    public FavoriteRoute save(FavoriteRoute favoriteRoute) {
        return favoriteRouteRepository.save(favoriteRoute);
    }

    public List<FavoriteRoute> findByUser(User user) {
        return favoriteRouteRepository.findByUserAndDeletedAtIsNull(user);
    }

    public Optional<FavoriteRoute> findByIdAndUser(Long id, User user) {
        return favoriteRouteRepository.findByIdAndUserAndDeletedAtIsNull(id, user);
    }
}
