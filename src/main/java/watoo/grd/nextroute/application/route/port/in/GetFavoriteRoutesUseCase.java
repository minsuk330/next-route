package watoo.grd.nextroute.application.route.port.in;

import watoo.grd.nextroute.application.route.dto.FavoriteResponse;

import java.util.List;

public interface GetFavoriteRoutesUseCase {
    List<FavoriteResponse> getAll(String deviceId);
}
