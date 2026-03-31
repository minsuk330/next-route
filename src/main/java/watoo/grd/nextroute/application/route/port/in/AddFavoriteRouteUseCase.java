package watoo.grd.nextroute.application.route.port.in;

import watoo.grd.nextroute.application.route.dto.FavoriteRequest;
import watoo.grd.nextroute.application.route.dto.FavoriteResponse;

public interface AddFavoriteRouteUseCase {
    FavoriteResponse add(String deviceId, FavoriteRequest request);
}
