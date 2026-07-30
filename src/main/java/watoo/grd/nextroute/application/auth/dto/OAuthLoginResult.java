package watoo.grd.nextroute.application.auth.dto;

import watoo.grd.nextroute.domain.auth.entity.AuthProvider;

/**
 * 앱 OAuth 로그인 성공 결과. accessToken/refreshToken 은 우리 서버가 발급한 자체 JWT 다.
 *
 * <p>토스 로그인({@link LoginResult})과 달리 외부 사용자 번호를 응답에 싣지 않는다.
 * 앱이 알 필요가 없고, 신원의 기준은 {@code userId} 이기 때문이다.
 */
public record OAuthLoginResult(
        String accessToken,
        String refreshToken,
        long userId,
        AuthProvider provider
) { }
