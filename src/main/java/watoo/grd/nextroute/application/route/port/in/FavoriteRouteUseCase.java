package watoo.grd.nextroute.application.route.port.in;

import watoo.grd.nextroute.application.route.dto.FavoriteRequest;
import watoo.grd.nextroute.application.route.dto.FavoriteResponse;

import java.util.List;

/** 사용자의 즐겨찾는 경로를 생성·조회·삭제한다. */
public interface FavoriteRouteUseCase {
    FavoriteResponse add(long userId, FavoriteRequest request);

    List<FavoriteResponse> getAll(long userId);

    void delete(long userId, Long favoriteId);
}
