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
 * 카카오는 서명된 신원 토큰 대신 접근 토큰을 준다. 그래서 서버가 카카오에 직접 물어보고,
 * 응답이 와야만 통과시킨다. 확인하지 못했으면 통과시키지 않는 것이 이 테스트의 핵심이다.
 */
class KakaoIdentityVerifierTest {

    private MockWebServer kakao;
    private KakaoIdentityVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        kakao = new MockWebServer();
        kakao.start();

        OAuthProviderProperties props = new OAuthProviderProperties();
        props.getKakao().setUserInfoUri(kakao.url("/v2/user/me").toString());
        verifier = new KakaoIdentityVerifier(RestClient.builder(), props);
    }

    @AfterEach
    void tearDown() throws Exception {
        kakao.shutdown();
    }

    @Test
    @DisplayName("정상 응답이면 id 를 사용자 번호로 쓴다")
    void validToken_usesIdAsSubject() throws Exception {
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

        RecordedRequest request = kakao.takeRequest();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer valid-token");
    }

    @Test
    @DisplayName("동의하지 않아 이메일·닉네임이 없어도 사용자 번호만 있으면 통과한다")
    void missingOptionalFields_stillPasses() {
        kakao.enqueue(json("{ \"id\": 42 }"));

        VerifiedIdentity identity = verify("valid-token");

        assertThat(identity.subject()).isEqualTo("42");
        assertThat(identity.email()).isNull();
        assertThat(identity.name()).isNull();
    }

    @Test
    @DisplayName("모르는 필드가 늘어나도 깨지지 않는다")
    void unknownFields_ignored() {
        kakao.enqueue(json("{ \"id\": 7, \"connected_at\": \"2026-01-01T00:00:00Z\", \"새필드\": 1 }"));

        assertThat(verify("valid-token").subject()).isEqualTo("7");
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
    @DisplayName("응답에 사용자 번호가 없으면 거부한다")
    void missingId_rejected() {
        kakao.enqueue(json("{ \"kakao_account\": { \"email\": \"a@b.c\" } }"));

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
        return verifier.verify(new OAuthCredential(AuthProvider.KAKAO, accessToken, null, null));
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
