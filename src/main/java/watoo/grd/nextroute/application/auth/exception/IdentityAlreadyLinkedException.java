package watoo.grd.nextroute.application.auth.exception;

import watoo.grd.nextroute.domain.auth.entity.AuthProvider;

/**
 * 계정당 앱 로그인 수단은 하나인데 두 번째를 붙이려 했다. 409 로 나간다.
 *
 * <p>로그인 경로에서는 발생하지 않는다 — 처음 보는 번호는 언제나 새 계정을 만들기 때문이다.
 * 나중에 연결 API 가 생겼을 때 코드 실수를 DB 가 막아 주는 자리다.
 */
public class IdentityAlreadyLinkedException extends RuntimeException {

    public IdentityAlreadyLinkedException(AuthProvider provider) {
        super("이미 다른 앱 로그인 수단이 연결된 계정이다: " + provider);
    }
}
