package watoo.grd.nextroute.application.auth.exception;

/** 애플·카카오 신원이 없는 계정(토스 전용 등)에는 기기를 연결하지 않는다. */
public class DeviceLinkNotAllowedException extends RuntimeException {

    public DeviceLinkNotAllowedException() {
        super("이 계정에는 기기를 연결할 수 없다");
    }
}
