package watoo.grd.nextroute.application.auth.exception;

/** 기기 비밀값 형식이 틀렸다. 원문은 메시지에 넣지 않는다. */
public class InvalidDeviceSecretException extends RuntimeException {

    public InvalidDeviceSecretException() {
        super("기기 비밀값 형식이 올바르지 않다");
    }
}
