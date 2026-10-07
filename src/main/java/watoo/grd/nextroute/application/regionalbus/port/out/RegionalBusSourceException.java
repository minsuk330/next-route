package watoo.grd.nextroute.application.regionalbus.port.out;

/**
 * 노선 원천 호출 실패.
 *
 * @see #blocking() 포털 공통 오류(키 미등록·한도 초과 등)라 그날 계속 실패하는 경우. 적재를 멈춘다
 * @see #retryable() 동시 세션 한도(TAGO resultCode 99)처럼 잠시 뒤 다시 하면 되는 경우
 */
public class RegionalBusSourceException extends RuntimeException {

    private final boolean blocking;
    private final boolean retryable;

    public RegionalBusSourceException(String message, boolean blocking, boolean retryable) {
        super(message);
        this.blocking = blocking;
        this.retryable = retryable;
    }

    public static RegionalBusSourceException blocking(String message) {
        return new RegionalBusSourceException(message, true, false);
    }

    public static RegionalBusSourceException retryable(String message) {
        return new RegionalBusSourceException(message, false, true);
    }

    public static RegionalBusSourceException failed(String message) {
        return new RegionalBusSourceException(message, false, false);
    }

    public boolean blocking() {
        return blocking;
    }

    public boolean retryable() {
        return retryable;
    }
}
