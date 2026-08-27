package watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;

/**
 * 앱 OAuth 로그인 요청.
 *
 * <p>{@code provider} 로 받을 수 있는 값은 APPLE·KAKAO 뿐이다. TOSS 를 지정하면 400 이다 —
 * 토스는 미니앱 안에서만 인가코드를 얻을 수 있어 앱에서는 반드시 실패한다.
 */
@Getter
@Setter
@NoArgsConstructor
public class OAuthLoginRequest {

    @NotNull
    private AuthProvider provider;

    /** APPLE 은 identity token, KAKAO 는 access token. */
    @NotBlank
    private String credential;

    /** APPLE 필수. /api/auth/oauth/challenge 로 받은 값. */
    private String nonce;

    /** 애플 최초 로그인에서만 앱이 채운다. 표시용이며 신원 판단에 쓰지 않는다. */
    private String displayName;

    /**
     * APPLE 필수. ASAuthorization 이 매 로그인 발급하는 값이며, 서버가 refresh_token 으로 교환해
     * 탈퇴 시 revoke 에 쓴다. 없으면 400.
     */
    private String authorizationCode;
}
