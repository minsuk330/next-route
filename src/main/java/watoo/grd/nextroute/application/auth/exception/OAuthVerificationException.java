package watoo.grd.nextroute.application.auth.exception;

/** provider 토큰 검증 실패. 401 로 나간다. 어떤 항목이 틀렸는지는 응답에 싣지 않는다. */
public class OAuthVerificationException extends RuntimeException {

    public OAuthVerificationException(String message) {
        super(message);
    }

    public OAuthVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
