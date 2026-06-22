package watoo.grd.nextroute.application.route.port.in;

public interface DeleteFavoriteRouteUseCase {
    void delete(long userId, Long favoriteId);
}
