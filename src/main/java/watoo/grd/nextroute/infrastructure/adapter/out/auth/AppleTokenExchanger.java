package watoo.grd.nextroute.infrastructure.adapter.out.auth;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.auth.config.OAuthProviderProperties;
import watoo.grd.nextroute.application.auth.port.out.AppleRefreshTokenPort;

import java.util.Map;

/**
 * 애플 authorization code 를 refresh_token 으로 교환한다.
 *
 * <p>{@code POST /auth/token} (x-www-form-urlencoded). 네이티브 앱 흐름이라 redirect_uri 는 없다.
 * client_secret 은 매 호출 {@link AppleClientSecretJwtBuilder} 로 새로 서명한다.
 */
@Component
public class AppleTokenExchanger implements AppleRefreshTokenPort {

    private final RestClient appleRestClient;
    private final AppleClientSecretJwtBuilder clientSecretBuilder;
    private final OAuthProviderProperties.Apple apple;

    public AppleTokenExchanger(@Qualifier("appleRestClient") RestClient appleRestClient,
                               AppleClientSecretJwtBuilder clientSecretBuilder,
                               OAuthProviderProperties properties) {
        this.appleRestClient = appleRestClient;
        this.clientSecretBuilder = clientSecretBuilder;
        this.apple = properties.getApple();
    }

    /**
     * code 를 교환해 refresh_token 을 돌려준다. 응답에 refresh_token 이 없으면 예외.
     *
     * <p>호출부(로그인)는 이 예외를 잡아 로그만 남기고 로그인은 성공시킨다 — 교환 실패가 로그인을
     * 막지 않게 한다.
     */
    @Override
    public String exchangeForRefreshToken(String authorizationCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", authorizationCode);
        form.add("client_id", apple.getClientId());
        form.add("client_secret", clientSecretBuilder.build());

        Map<String, Object> body = appleRestClient.post()
                .uri("/auth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

        Object refreshToken = body == null ? null : body.get("refresh_token");
        if (refreshToken == null || refreshToken.toString().isBlank()) {
            throw new IllegalStateException("애플 토큰 교환 응답에 refresh_token 이 없다");
        }
        return refreshToken.toString();
    }
}
