package watoo.grd.nextroute.application.route.exception;

/** HOME/WORK 즐겨찾기 유저당 1개 제약 위반. */
public class FavoriteConflictException extends RuntimeException {
    public FavoriteConflictException(String message) {
        super(message);
    }
}
