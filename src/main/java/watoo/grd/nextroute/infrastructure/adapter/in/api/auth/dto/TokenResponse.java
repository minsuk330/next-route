package watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto;

import watoo.grd.nextroute.application.auth.dto.TokenResult;

/** 앱 재발급 응답. 토스 미니앱은 {@link TossTokenResponse} 를 그대로 쓴다. */
public record TokenResponse(String accessToken, String refreshToken) {

    public static TokenResponse from(TokenResult r) {
        return new TokenResponse(r.accessToken(), r.refreshToken());
    }
}
