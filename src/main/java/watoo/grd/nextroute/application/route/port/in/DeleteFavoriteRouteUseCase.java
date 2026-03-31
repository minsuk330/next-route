package watoo.grd.nextroute.application.route.port.in;

public interface DeleteFavoriteRouteUseCase {
    void delete(String deviceId, Long favoriteId);
}
