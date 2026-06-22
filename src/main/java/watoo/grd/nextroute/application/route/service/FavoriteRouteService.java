package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.route.dto.FavoriteRequest;
import watoo.grd.nextroute.application.route.dto.FavoriteResponse;
import watoo.grd.nextroute.application.route.port.in.AddFavoriteRouteUseCase;
import watoo.grd.nextroute.application.route.port.in.DeleteFavoriteRouteUseCase;
import watoo.grd.nextroute.application.route.port.in.GetFavoriteRoutesUseCase;
import watoo.grd.nextroute.application.route.exception.FavoriteConflictException;
import watoo.grd.nextroute.domain.route.favorite.entity.FavoriteRoute;
import watoo.grd.nextroute.domain.route.favorite.entity.FavoriteType;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.util.List;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FavoriteRouteService
        implements AddFavoriteRouteUseCase, GetFavoriteRoutesUseCase, DeleteFavoriteRouteUseCase {

    private final watoo.grd.nextroute.domain.route.favorite.service.FavoriteRouteService favoriteDomainService;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public FavoriteResponse add(long userId, FavoriteRequest request) {
        User user = requireUser(userId);
        if (request.getType() != FavoriteType.ETC
                && favoriteDomainService.existsActiveType(user, request.getType())) {
            throw new FavoriteConflictException(
                    request.getType() + " 즐겨찾기는 이미 존재합니다.");
        }
        FavoriteRoute saved = favoriteDomainService.save(
                FavoriteRoute.builder()
                        .user(user)
                        .type(request.getType())
                        .name(request.getName())
                        .address(request.getAddress())
                        .endPlace(request.getEndPlace())
                        .ex(request.getEx())
                        .ey(request.getEy())
                        .endDate(request.getEndDate())
                        .build()
        );
        return FavoriteResponse.from(saved);
    }

    @Override
    public List<FavoriteResponse> getAll(long userId) {
        return userRepository.findById(userId)
                .map(user -> favoriteDomainService.findByUser(user).stream()
                        .map(FavoriteResponse::from)
                        .toList())
                .orElse(List.of());
    }

    @Override
    @Transactional
    public void delete(long userId, Long favoriteId) {
        User user = requireUser(userId);
        FavoriteRoute route = favoriteDomainService.findByIdAndUser(favoriteId, user)
                .orElseThrow(() -> new NoSuchElementException("즐겨찾기를 찾을 수 없습니다."));
        route.markDeleted();
    }

    private User requireUser(long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + userId));
    }
}
