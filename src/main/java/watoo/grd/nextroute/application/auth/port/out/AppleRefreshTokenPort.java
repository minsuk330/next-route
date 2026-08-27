package watoo.grd.nextroute.application.auth.port.out;

/**
 * 애플 authorization code 를 refresh_token 으로 교환한다.
 *
 * <p>탈퇴 시 애플 연결을 끊으려면(revoke) refresh_token 이 있어야 하는데, 그 토큰은 로그인 때 code
 * 를 애플에 교환해야만 나온다. 구현은 인프라(RestClient)에 둔다.
 */
public interface AppleRefreshTokenPort {

    /**
     * code 를 교환해 refresh_token 을 돌려준다.
     *
     * @throws RuntimeException 교환에 실패했을 때(호출부가 잡아 로그만 남기고 로그인은 성공시킨다)
     */
    String exchangeForRefreshToken(String authorizationCode);
}
