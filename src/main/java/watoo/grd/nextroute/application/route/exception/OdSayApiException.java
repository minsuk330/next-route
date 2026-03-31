package watoo.grd.nextroute.application.route.exception;

import lombok.Getter;

@Getter
public class OdSayApiException extends RuntimeException {
    private final int errorCode;

    public OdSayApiException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
