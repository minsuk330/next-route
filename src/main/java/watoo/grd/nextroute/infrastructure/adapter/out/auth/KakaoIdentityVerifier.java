package watoo.grd.nextroute.infrastructure.adapter.out.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import watoo.grd.nextroute.application.auth.config.OAuthProviderProperties;
import watoo.grd.nextroute.application.auth.dto.OAuthCredential;
import watoo.grd.nextroute.application.auth.dto.VerifiedIdentity;
import watoo.grd.nextroute.application.auth.exception.OAuthVerificationException;
import watoo.grd.nextroute.application.auth.port.out.OAuthIdentityVerifier;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;

/**
 * 카카오 로그인 토큰 검증.
 *
 * <p>카카오는 서명된 신원 토큰 대신 접근 토큰을 준다. 그래서 서버가 카카오에 직접 물어본다 —
 * 응답이 오면 살아 있는 토큰이고, 그 응답의 {@code id} 가 사용자 번호다.
 *
 * <p>관측성 계측이 붙도록 {@code RestClient} 를 쓴다(기존 어댑터 관례).
 */
@Slf4j
@Component
public class KakaoIdentityVerifier implements OAuthIdentityVerifier {

    private final RestClient restClient;
    private final String userInfoUri;

    public KakaoIdentityVerifier(RestClient.Builder builder, OAuthProviderProperties properties) {
        this.restClient = builder.build();
        this.userInfoUri = properties.getKakao().getUserInfoUri();
    }

    @Override
    public AuthProvider provider() {
        return AuthProvider.KAKAO;
    }

    @Override
    public VerifiedIdentity verify(OAuthCredential credential) {
        String accessToken = credential.credential();
        if (accessToken == null || accessToken.isBlank()) {
            throw new OAuthVerificationException("카카오 토큰이 비어 있다");
        }

        KakaoUser user = fetchUser(accessToken);
        if (user == null || user.id() == null) {
            throw new OAuthVerificationException("카카오가 사용자 번호를 주지 않았다");
        }

        return new VerifiedIdentity(
                AuthProvider.KAKAO,
                String.valueOf(user.id()),
                user.email(),
                user.nickname());
    }

    private KakaoUser fetchUser(String accessToken) {
        try {
            return restClient.get()
                    .uri(userInfoUri)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(KakaoUser.class);
        } catch (RestClientException e) {
            // 401·403 은 토큰이 유효하지 않다는 뜻이고, 그 밖의 실패도 신원을 확인하지 못한 것은 같다.
            // 확인하지 못했으면 통과시키지 않는다.
            log.debug("[Kakao] 사용자 조회 실패: {}", e.getMessage());
            throw new OAuthVerificationException("카카오 토큰 검증 실패", e);
        }
    }

    /** 카카오 응답 중 필요한 것만 받는다. 나머지 필드가 늘어나도 깨지지 않게 한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoUser(Long id, @JsonProperty("kakao_account") KakaoAccount account) {

        String email() {
            return account == null ? null : account.email();
        }

        String nickname() {
            return account == null || account.profile() == null ? null : account.profile().nickname();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoAccount(String email, KakaoProfile profile) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoProfile(String nickname) {
    }
}
