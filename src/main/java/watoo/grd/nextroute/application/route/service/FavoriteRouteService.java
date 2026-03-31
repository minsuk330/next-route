package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.route.dto.FavoriteRequest;
import watoo.grd.nextroute.application.route.dto.FavoriteResponse;
import watoo.grd.nextroute.application.route.port.in.AddFavoriteRouteUseCase;
import watoo.grd.nextroute.application.route.port.in.DeleteFavoriteRouteUseCase;
import watoo.grd.nextroute.application.route.port.in.GetFavoriteRoutesUseCase;
import watoo.grd.nextroute.domain.route.favorite.entity.FavoriteRoute;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.service.UserDomainService;

import java.util.List;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FavoriteRouteService
        implements AddFavoriteRouteUseCase, GetFavoriteRoutesUseCase, DeleteFavoriteRouteUseCase {

    private final watoo.grd.nextroute.domain.route.favorite.service.FavoriteRouteService favoriteDomainService;
    private final UserDomainService userDomainService;

    @Override
    @Transactional
    public FavoriteResponse add(String deviceId, FavoriteRequest request) {
        User user = userDomainService.findOrCreate(deviceId);
        FavoriteRoute saved = favoriteDomainService.save(
                FavoriteRoute.builder()
                        .user(user)
                        .type(request.getType())
                        .endPlace(request.getEndPlace())
                        .ex(request.getEx())
                        .ey(request.getEy())
                        .endDate(request.getEndDate())
                        .build()
        );
        return FavoriteResponse.from(saved);
    }

    @Override
    public List<FavoriteResponse> getAll(String deviceId) {
        return userDomainService.findOnly(deviceId)
                .map(user -> favoriteDomainService.findByUser(user).stream()
                        .map(FavoriteResponse::from)
                        .toList())
                .orElse(List.of());
    }

    @Override
    @Transactional
    public void delete(String deviceId, Long favoriteId) {
        User user = userDomainService.findOrCreate(deviceId);
        FavoriteRoute route = favoriteDomainService.findByIdAndUser(favoriteId, user)
                .orElseThrow(() -> new NoSuchElementException("즐겨찾기를 찾을 수 없습니다."));
        route.markDeleted();
    }
}
