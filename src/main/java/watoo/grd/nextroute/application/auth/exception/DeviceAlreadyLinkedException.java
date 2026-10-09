package watoo.grd.nextroute.application.auth.exception;

/** 이 기기 비밀값이 이미 다른 계정에 묶여 있다(연결 요청에서만 발생). */
public class DeviceAlreadyLinkedException extends RuntimeException {

    public DeviceAlreadyLinkedException() {
        super("이 기기는 이미 다른 계정에 연결돼 있다");
    }
}
