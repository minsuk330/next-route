package watoo.grd.nextroute.infrastructure.adapter.out.auth;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.auth.config.OAuthProviderProperties;
import watoo.grd.nextroute.application.auth.dto.OAuthCredential;
import watoo.grd.nextroute.application.auth.dto.VerifiedIdentity;
import watoo.grd.nextroute.application.auth.exception.OAuthVerificationException;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 카카오는 서명된 신원 토큰 대신 불투명한 접근 토큰을 준다. 그래서 서버가 카카오에 직접 물어본다.
 *
 * <p>두 가지를 확인한다. <b>이 토큰이 우리 앱을 위해 발급됐는가</b>(app_id)와
 * 살아 있는 토큰인가다. 앞의 것이 없으면 다른 앱이 발급받은 토큰이 그대로 통과한다 —
 * 애플의 {@code aud} 검사가 없는 것과 같다.
 */
class KakaoIdentityVerifierTest {

    private static final long OUR_APP_ID = 123456L;

    private MockWebServer kakao;
    private KakaoIdentityVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        kakao = new MockWebServer();
        kakao.start();

        OAuthProviderProperties props = new OAuthProviderProperties();
        props.getKakao().setTokenInfoUri(kakao.url("/v1/user/access_token_info").toString());
        props.getKakao().setUserInfoUri(kakao.url("/v2/user/me").toString());
        props.getKakao().setAppId(OUR_APP_ID);
        verifier = new KakaoIdentityVerifier(RestClient.builder(), props);
    }

    @AfterEach
    void tearDown() throws Exception {
        kakao.shutdown();
    }

    // ── 통과 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("우리 앱 토큰이면 id 를 사용자 번호로 쓴다")
    void ourAppToken_usesIdAsSubject() throws Exception {
        kakao.enqueue(json("{ \"id\": 1234567890, \"app_id\": 123456, \"expires_in\": 3600 }"));
        kakao.enqueue(json("""
                {
                  "id": 1234567890,
                  "kakao_account": {
                    "email": "user@kakao.com",
                    "profile": { "nickname": "길동" }
                  }
                }
                """));

        VerifiedIdentity identity = verify("valid-token");

        assertThat(identity.provider()).isEqualTo(AuthProvider.KAKAO);
        assertThat(identity.subject()).isEqualTo("1234567890");
        assertThat(identity.email()).isEqualTo("user@kakao.com");
        assertThat(identity.name()).isEqualTo("길동");

        RecordedRequest first = kakao.takeRequest();
        assertThat(first.getPath()).contains("access_token_info");
        assertThat(first.getHeader("Authorization")).isEqualTo("Bearer valid-token");
    }

    @Test
    @DisplayName("동의하지 않아 이메일·닉네임이 없어도 통과한다")
    void missingOptionalFields_stillPasses() {
        kakao.enqueue(json("{ \"id\": 42, \"app_id\": 123456 }"));
        kakao.enqueue(json("{ \"id\": 42 }"));

        VerifiedIdentity identity = verify("valid-token");

        assertThat(identity.subject()).isEqualTo("42");
        assertThat(identity.email()).isNull();
        assertThat(identity.name()).isNull();
    }

    @Test
    @DisplayName("모르는 필드가 늘어나도 깨지지 않는다")
    void unknownFields_ignored() {
        kakao.enqueue(json("{ \"id\": 7, \"app_id\": 123456, \"새필드\": 1 }"));
        kakao.enqueue(json("{ \"id\": 7, \"connected_at\": \"2026-01-01T00:00:00Z\" }"));

        assertThat(verify("valid-token").subject()).isEqualTo("7");
    }

    // ── 대상 앱 검증 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("다른 앱이 발급받은 토큰은 거부한다 — 애플의 aud 검사에 해당")
    void otherAppToken_rejected() {
        kakao.enqueue(json("{ \"id\": 1234567890, \"app_id\": 999999 }"));

        assertThatThrownBy(() -> verify("someone-elses-token"))
                .isInstanceOf(OAuthVerificationException.class)
                .hasMessageContaining("우리 앱");
    }

    @Test
    @DisplayName("대상 앱이 다르면 프로필을 조회하지도 않는다 — 검사 순서")
    void otherAppToken_doesNotFetchProfile() {
        kakao.enqueue(json("{ \"id\": 1, \"app_id\": 999999 }"));

        assertThatThrownBy(() -> verify("someone-elses-token"))
                .isInstanceOf(OAuthVerificationException.class);

        assertThat(kakao.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("app_id 가 응답에 없으면 거부한다 — 확인하지 못한 것은 다른 앱인 것과 같다")
    void missingAppId_rejected() {
        kakao.enqueue(json("{ \"id\": 1234567890 }"));

        assertThatThrownBy(() -> verify("weird-token"))
                .isInstanceOf(OAuthVerificationException.class)
                .hasMessageContaining("우리 앱");
    }

    // ── 거부 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("카카오가 401 을 주면 거부한다 — 만료·위조 토큰")
    void unauthorized_rejected() {
        kakao.enqueue(new MockResponse().setResponseCode(401));

        assertThatThrownBy(() -> verify("expired-token"))
                .isInstanceOf(OAuthVerificationException.class);
    }

    @Test
    @DisplayName("카카오가 5xx 를 주면 거부한다 — 확인하지 못했으면 통과시키지 않는다")
    void serverError_rejected() {
        kakao.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> verify("any-token"))
                .isInstanceOf(OAuthVerificationException.class);
    }

    @Test
    @DisplayName("프로필 조회가 실패해도 거부한다")
    void profileFetchFailure_rejected() {
        kakao.enqueue(json("{ \"id\": 42, \"app_id\": 123456 }"));
        kakao.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> verify("valid-token"))
                .isInstanceOf(OAuthVerificationException.class);
    }

    @Test
    @DisplayName("응답에 사용자 번호가 없으면 거부한다")
    void missingId_rejected() {
        kakao.enqueue(json("{ \"app_id\": 123456 }"));

        assertThatThrownBy(() -> verify("weird-token"))
                .isInstanceOf(OAuthVerificationException.class)
                .hasMessageContaining("사용자 번호");
    }

    @Test
    @DisplayName("토큰이 비어 있으면 카카오를 부르지도 않는다")
    void blankToken_rejectedWithoutCall() {
        assertThatThrownBy(() -> verify(null)).isInstanceOf(OAuthVerificationException.class);
        assertThatThrownBy(() -> verify("")).isInstanceOf(OAuthVerificationException.class);

        assertThat(kakao.getRequestCount()).isZero();
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private VerifiedIdentity verify(String accessToken) {
        return verifier.verify(new OAuthCredential(AuthProvider.KAKAO, accessToken, null, null, null));
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
