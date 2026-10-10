package watoo.grd.nextroute.application.auth.exception;

/** 같은 출발지의 기기 가입이 시간당 한도를 넘었다. */
public class DeviceSignupRateLimitedException extends RuntimeException {

    public DeviceSignupRateLimitedException() {
        super("잠시 후 다시 시도해 달라");
    }
}
