package watoo.grd.nextroute.infrastructure.adapter.out.auth;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.auth.config.OAuthProviderProperties;
import watoo.grd.nextroute.application.auth.port.out.AppleRevokePort;

/**
 * 애플 연결 폐기(revoke)를 호출한다.
 *
 * <p>{@code POST /auth/revoke} (x-www-form-urlencoded). 성공은 본문 없는 200 이다.
 * client_secret 은 매 호출 {@link AppleClientSecretJwtBuilder} 로 새로 서명한다
 * ({@link AppleTokenExchanger} 와 같은 방식).
 */
@Component
public class AppleTokenRevoker implements AppleRevokePort {

    private final RestClient appleRestClient;
    private final AppleClientSecretJwtBuilder clientSecretBuilder;
    private final OAuthProviderProperties.Apple apple;

    public AppleTokenRevoker(@Qualifier("appleRestClient") RestClient appleRestClient,
                             AppleClientSecretJwtBuilder clientSecretBuilder,
                             OAuthProviderProperties properties) {
        this.appleRestClient = appleRestClient;
        this.clientSecretBuilder = clientSecretBuilder;
        this.apple = properties.getApple();
    }

    /**
     * refresh_token 으로 연결을 끊는다. 애플이 4xx/5xx 를 주면 RestClient 가 예외를 던진다 —
     * 호출부(탈퇴 조율)가 잡아 로그만 남긴다(best-effort).
     */
    @Override
    public void revoke(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", apple.getClientId());
        form.add("client_secret", clientSecretBuilder.build());
        form.add("token", refreshToken);
        form.add("token_type_hint", "refresh_token");

        appleRestClient.post()
                .uri("/auth/revoke")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity();
    }
}
