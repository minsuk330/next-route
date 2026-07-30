package watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto;

import watoo.grd.nextroute.application.auth.dto.OAuthLoginResult;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;

public record OAuthLoginResponse(String accessToken, String refreshToken,
                                 long userId, AuthProvider provider) {

    public static OAuthLoginResponse from(OAuthLoginResult r) {
        return new OAuthLoginResponse(r.accessToken(), r.refreshToken(), r.userId(), r.provider());
    }
}
