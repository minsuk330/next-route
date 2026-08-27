package watoo.grd.nextroute.application.auth.dto;

import watoo.grd.nextroute.domain.auth.entity.AuthProvider;

/**
 * 앱이 보내는 로그인 자격증명.
 *
 * @param credential  provider 가 발급한 토큰. APPLE 은 identity token(JWT), KAKAO 는 access token.
 * @param nonce       APPLE 전용. 서버가 challenge 로 발급한 값이며 로그인 시 한 번만 소비된다.
 * @param displayName 애플 최초 로그인에서만 앱이 채워 보낸다. 표시용이며 신원 판단에 쓰지 않는다.
 *                    애플은 이름을 토큰에 담지 않으므로 서버가 스스로 얻을 방법이 없다.
 * @param authorizationCode APPLE 전용·필수. refresh_token 교환에 쓴다(탈퇴 시 revoke 용).
 */
public record OAuthCredential(AuthProvider provider, String credential,
                              String nonce, String displayName, String authorizationCode) {
}
