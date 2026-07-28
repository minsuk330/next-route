package watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto;

/**
 * 애플 로그인에 쓸 일회용 값.
 *
 * @param nonce            앱이 애플 로그인에 그대로 넘기고, 로그인 요청에 함께 실어 보낸다.
 * @param expiresInSeconds 이 시간이 지나면 소비할 수 없다.
 */
public record OAuthChallengeResponse(String nonce, long expiresInSeconds) {
}
