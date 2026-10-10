package watoo.grd.nextroute.application.auth.exception;

/** 한 계정에 연결할 수 있는 기기 수를 넘었다. */
public class DeviceLinkLimitExceededException extends RuntimeException {

    public DeviceLinkLimitExceededException() {
        super("연결할 수 있는 기기 수를 넘었다");
    }
}
