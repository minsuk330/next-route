package watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto;

import lombok.Builder;
import lombok.Getter;
import watoo.grd.nextroute.application.auth.dto.LoginResult;

@Getter
@Builder
public class TossLoginResponse {

    private final String accessToken;
    private final String refreshToken;
    private final long userId;
    private final long tossUserKey;
    private final String name;

    public static TossLoginResponse from(LoginResult r) {
        return TossLoginResponse.builder()
                .accessToken(r.accessToken())
                .refreshToken(r.refreshToken())
                .userId(r.userId())
                .tossUserKey(r.tossUserKey())
                .name(r.name())
                .build();
    }
}
