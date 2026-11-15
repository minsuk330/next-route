package watoo.grd.nextroute.application.regionalsubway.port.out;

/**
 * 시간표 원천 호출 실패. 역 하나의 실패는 그 역만 옛 값으로 두고 넘어간다.
 *
 * @see #blocking() 포털 공통 오류(키 미등록 등)라 같은 원천의 남은 역도 계속 실패하는 경우
 * @see #retryable() 시간 초과, 동시 세션 한도(TAGO 99)처럼 잠시 뒤 다시 하면 되는 경우
 */
public class RegionalSourceException extends RuntimeException {

    private final boolean blocking;
    private final boolean retryable;

    private RegionalSourceException(String message, boolean blocking, boolean retryable) {
        super(message);
        this.blocking = blocking;
        this.retryable = retryable;
    }

    public static RegionalSourceException blocking(String message) {
        return new RegionalSourceException(message, true, false);
    }

    public static RegionalSourceException retryable(String message) {
        return new RegionalSourceException(message, false, true);
    }

    public static RegionalSourceException failed(String message) {
        return new RegionalSourceException(message, false, false);
    }

    public boolean blocking() {
        return blocking;
    }

    public boolean retryable() {
        return retryable;
    }
}
