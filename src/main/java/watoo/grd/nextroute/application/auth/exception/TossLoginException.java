package watoo.grd.nextroute.application.auth.exception;

import lombok.Getter;

/** 토스 로그인 API 호출 실패. invalidGrant=true면 인가코드/토큰 만료·중복 사용. */
@Getter
public class TossLoginException extends RuntimeException {

    private final boolean invalidGrant;

    public TossLoginException(String message, boolean invalidGrant) {
        super(message);
        this.invalidGrant = invalidGrant;
    }

    public TossLoginException(String message, boolean invalidGrant, Throwable cause) {
        super(message, cause);
        this.invalidGrant = invalidGrant;
    }
}
