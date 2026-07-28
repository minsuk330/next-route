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

import java.util.Objects;

/**
 * 카카오 로그인 토큰 검증.
 *
 * <p>카카오는 서명된 신원 토큰 대신 <b>불투명한 접근 토큰</b>을 준다. 애플처럼 안을 열어
 * 발급 대상을 확인할 수 없으므로 서버가 카카오에 직접 물어본다. 두 번 부른다.
 *
 * <ol>
 *   <li>{@code access_token_info} — 이 토큰이 <b>우리 앱을 위해</b> 발급됐는지(app_id).
 *       애플의 {@code aud} 검사에 해당한다.</li>
 *   <li>{@code user/me} — 이메일·닉네임(표시용).</li>
 * </ol>
 *
 * <p>순서를 지킨다. app_id 가 다르면 프로필을 조회하지도 않는다.
 *
 * <p>관측성 계측이 붙도록 {@code RestClient} 를 쓴다(기존 어댑터 관례).
 */
@Slf4j
@Component
public class KakaoIdentityVerifier implements OAuthIdentityVerifier {

    private final RestClient restClient;
    private final String tokenInfoUri;
    private final String userInfoUri;
    private final Long appId;

    public KakaoIdentityVerifier(RestClient.Builder builder, OAuthProviderProperties properties) {
        OAuthProviderProperties.Kakao kakao = properties.getKakao();
        this.restClient = builder.build();
        this.tokenInfoUri = kakao.getTokenInfoUri();
        this.userInfoUri = kakao.getUserInfoUri();
        this.appId = kakao.getAppId();
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

        TokenInfo tokenInfo = fetch(tokenInfoUri, accessToken, TokenInfo.class, "토큰 정보");
        String subject = requireOurApp(tokenInfo);

        KakaoUser profile = fetch(userInfoUri, accessToken, KakaoUser.class, "사용자 정보");
        return new VerifiedIdentity(
                AuthProvider.KAKAO,
                subject,
                profile == null ? null : profile.email(),
                profile == null ? null : profile.nickname());
    }

    /**
     * 이 토큰이 우리 앱을 위해 발급됐는지 확인하고 사용자 번호를 돌려준다.
     *
     * <p>이 검사가 없으면 다른 앱이 발급받은 카카오 토큰이 그대로 통과한다. 공격자가 자기 앱에
     * 카카오 로그인을 붙여 피해자의 토큰을 받아 두고, 그것을 우리 서버로 보내면 되기 때문이다.
     */
    private String requireOurApp(TokenInfo info) {
        if (info == null || info.id() == null) {
            throw new OAuthVerificationException("카카오가 사용자 번호를 주지 않았다");
        }
        if (!Objects.equals(appId, info.appId())) {
            // 어느 앱 것인지는 응답에 싣지 않는다. 공격자에게 힌트가 된다.
            log.warn("[Kakao] 다른 앱을 위해 발급된 토큰 — 거부한다");
            throw new OAuthVerificationException("우리 앱을 위해 발급된 토큰이 아니다");
        }
        return String.valueOf(info.id());
    }

    private <T> T fetch(String uri, String accessToken, Class<T> type, String what) {
        try {
            return restClient.get()
                    .uri(uri)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(type);
        } catch (RestClientException e) {
            // 401·403 은 토큰이 유효하지 않다는 뜻이고, 그 밖의 실패도 신원을 확인하지 못한 것은 같다.
            // 확인하지 못했으면 통과시키지 않는다.
            log.debug("[Kakao] {} 조회 실패: {}", what, e.getMessage());
            throw new OAuthVerificationException("카카오 토큰 검증 실패", e);
        }
    }

    /** 토큰이 어느 앱의 누구를 위해 발급됐는지. 필요한 것만 받는다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenInfo(Long id, @JsonProperty("app_id") Long appId) {
    }

    /** 프로필. 나머지 필드가 늘어나도 깨지지 않게 한다. */
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
