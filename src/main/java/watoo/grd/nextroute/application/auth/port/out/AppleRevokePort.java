package watoo.grd.nextroute.application.auth.port.out;

/**
 * 애플에 "이 사용자↔우리앱 연결을 끊어라"를 알린다(revoke).
 *
 * <p>App Store 심사 지침 5.1.1(v): 애플 로그인 사용자가 탈퇴하면 서버가 토큰을 폐기해야 한다.
 * refresh_token 을 애플 {@code /auth/revoke} 에 보낸다. 구현은 인프라(RestClient)에 둔다.
 */
public interface AppleRevokePort {

    /**
     * refresh_token 으로 애플 연결을 끊는다. best-effort — 실패는 호출부가 잡아 로그만 남기고
     * 탈퇴는 계속 진행한다.
     *
     * @param refreshToken 애플 refresh_token(평문)
     * @throws RuntimeException 애플 호출이 실패했을 때
     */
    void revoke(String refreshToken);
}
