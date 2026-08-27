package watoo.grd.nextroute.application.auth.exception;

/**
 * 애플 로그인에 authorization code 가 빠졌다. 400 으로 나간다.
 *
 * <p>탈퇴 시 애플 연결을 끊으려면(revoke) 로그인 때 code 를 refresh_token 으로 교환해 둬야 한다.
 * code 없는 애플 로그인을 허용하면 그 사용자는 나중에 revoke 할 방법이 없어진다. 실사용자가 없는
 * 지금 필수로 못 박아 두는 편이 안전하다.
 */
public class MissingAuthorizationCodeException extends RuntimeException {

    public MissingAuthorizationCodeException() {
        super("애플 로그인에는 authorizationCode 가 필요하다");
    }
}
