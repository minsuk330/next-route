package watoo.grd.nextroute.application.route.exception;

/**
 * ML serving 호출 실패. retryable=true면 타임아웃/5xx 등 일시 장애,
 * false면 계약 위반(요청 422, 응답 불변식 위반)으로 재시도해도 무의미.
 */
public class MlPredictionException extends RuntimeException {

    private final int statusCode;
    private final boolean retryable;

    public MlPredictionException(int statusCode, String message, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public MlPredictionException(int statusCode, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
