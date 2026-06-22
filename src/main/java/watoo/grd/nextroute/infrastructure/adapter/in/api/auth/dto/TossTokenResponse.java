package watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto;

import lombok.Builder;
import lombok.Getter;
import watoo.grd.nextroute.application.auth.dto.TokenResult;

@Getter
@Builder
public class TossTokenResponse {

    private final String accessToken;
    private final String refreshToken;

    public static TossTokenResponse from(TokenResult r) {
        return TossTokenResponse.builder()
                .accessToken(r.accessToken())
                .refreshToken(r.refreshToken())
                .build();
    }
}
