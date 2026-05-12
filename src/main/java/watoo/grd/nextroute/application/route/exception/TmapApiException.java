package watoo.grd.nextroute.application.route.exception;

public class TmapApiException extends RuntimeException {

    private final int statusCode;

    public TmapApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public TmapApiException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
