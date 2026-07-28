package watoo.grd.nextroute.infrastructure.adapter.out.auth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.auth.config.OAuthProviderProperties;
import watoo.grd.nextroute.application.auth.dto.OAuthCredential;
import watoo.grd.nextroute.application.auth.dto.VerifiedIdentity;
import watoo.grd.nextroute.application.auth.exception.OAuthVerificationException;
import watoo.grd.nextroute.application.auth.port.out.OAuthChallengePort;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;

import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 애플 토큰 검증의 거부 매트릭스를 고정한다.
 *
 * <p>통과 케이스 하나보다 거부 케이스가 중요하다. 여기서 하나라도 새면 아무나 다른 사람 계정으로
 * 로그인할 수 있다. 실제 애플 자격증명 없이 테스트하려고 자체 RSA 키쌍을 만들어
 * MockWebServer 로 열쇠 목록을 흉내 낸다.
 */
class AppleIdentityVerifierTest {

    private static final String ISSUER = "https://appleid.apple.com";
    private static final String AUDIENCE = "com.golbob.nextroute";

    private static MockWebServer jwkServer;
    private static RSAKey signingKey;
    private static RSAKey otherKey;

    private StubChallengePort challengePort;
    private AppleIdentityVerifier verifier;

    @BeforeAll
    static void startJwkServer() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("test-key").generate();

        jwkServer = new MockWebServer();
        jwkServer.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                return new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(new JWKSet(signingKey.toPublicJWK()).toString());
            }
        });
        jwkServer.start();
    }

    @AfterAll
    static void stopJwkServer() throws Exception {
        jwkServer.shutdown();
    }

    @BeforeEach
    void setUp() {
        challengePort = new StubChallengePort();
        verifier = new AppleIdentityVerifier(
                new JwkDecoderFactory(), properties(List.of(AUDIENCE)), challengePort);
    }

    private OAuthProviderProperties properties(List<String> audiences) {
        OAuthProviderProperties props = new OAuthProviderProperties();
        props.getApple().setJwkSetUri(jwkServer.url("/keys").toString());
        props.getApple().setIssuer(ISSUER);
        props.getApple().setAudiences(audiences);
        return props;
    }

    // ── 통과 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("정상 토큰이면 사용자 번호와 이메일을 돌려준다")
    void validToken_passes() throws Exception {
        challengePort.store("nonce-1");
        String token = token(signingKey, ISSUER, AUDIENCE, "apple-sub-1", "nonce-1", plusHours(1));

        VerifiedIdentity identity = verifier.verify(
                new OAuthCredential(AuthProvider.APPLE, token, "nonce-1", "홍길동"));

        assertThat(identity.provider()).isEqualTo(AuthProvider.APPLE);
        assertThat(identity.subject()).isEqualTo("apple-sub-1");
        assertThat(identity.email()).isEqualTo("apple-sub-1@example.com");
    }

    @Test
    @DisplayName("이름은 토큰이 아니라 앱이 보낸 값을 쓴다 — 애플은 토큰에 이름을 담지 않는다")
    void nameComesFromRequest_notToken() throws Exception {
        challengePort.store("nonce-1");
        String token = token(signingKey, ISSUER, AUDIENCE, "apple-sub-1", "nonce-1", plusHours(1));

        VerifiedIdentity identity = verifier.verify(
                new OAuthCredential(AuthProvider.APPLE, token, "nonce-1", "앱이 보낸 이름"));

        assertThat(identity.name()).isEqualTo("앱이 보낸 이름");
    }

    // ── 거부 매트릭스 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("다른 열쇠로 서명한 토큰은 거부한다")
    void forgedSignature_rejected() throws Exception {
        challengePort.store("nonce-1");
        String token = token(otherKey, ISSUER, AUDIENCE, "sub", "nonce-1", plusHours(1));

        assertThatThrownBy(() -> verify(token, "nonce-1"))
                .isInstanceOf(OAuthVerificationException.class);
    }

    @Test
    @DisplayName("다른 앱을 위해 발급된 토큰은 거부한다")
    void wrongAudience_rejected() throws Exception {
        challengePort.store("nonce-1");
        String token = token(signingKey, ISSUER, "com.someone.else", "sub", "nonce-1", plusHours(1));

        assertThatThrownBy(() -> verify(token, "nonce-1"))
                .isInstanceOf(OAuthVerificationException.class);
    }

    @Test
    @DisplayName("발급처가 다르면 거부한다")
    void wrongIssuer_rejected() throws Exception {
        challengePort.store("nonce-1");
        String token = token(signingKey, "https://evil.example.com", AUDIENCE, "sub", "nonce-1", plusHours(1));

        assertThatThrownBy(() -> verify(token, "nonce-1"))
                .isInstanceOf(OAuthVerificationException.class);
    }

    @Test
    @DisplayName("만료된 토큰은 거부한다")
    void expiredToken_rejected() throws Exception {
        challengePort.store("nonce-1");
        String token = token(signingKey, ISSUER, AUDIENCE, "sub", "nonce-1", plusHours(-1));

        assertThatThrownBy(() -> verify(token, "nonce-1"))
                .isInstanceOf(OAuthVerificationException.class);
    }

    @Test
    @DisplayName("토큰의 nonce 와 요청의 nonce 가 다르면 거부한다")
    void nonceMismatch_rejected() throws Exception {
        challengePort.store("nonce-1");
        // 다른 challenge 로 발급된 토큰을 붙여 보내는 상황
        String token = token(signingKey, ISSUER, AUDIENCE, "sub", "nonce-token", plusHours(1));

        assertThatThrownBy(() -> verify(token, "nonce-1"))
                .isInstanceOf(OAuthVerificationException.class)
                .hasMessageContaining("nonce");
    }

    @Test
    @DisplayName("토큰에 원본이 그대로 들어 있으면 거부한다 — 해시를 기대한다")
    void rawNonceInToken_rejected() throws Exception {
        challengePort.store("nonce-1");
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(AUDIENCE).subject("sub")
                .claim("nonce", "nonce-1")            // 해시가 아니라 원본
                .issueTime(new Date())
                .expirationTime(Date.from(plusHours(1)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(signingKey.getKeyID()).type(JOSEObjectType.JWT).build(),
                claims);
        jwt.sign(new RSASSASigner(signingKey));

        assertThatThrownBy(() -> verify(jwt.serialize(), "nonce-1"))
                .isInstanceOf(OAuthVerificationException.class)
                .hasMessageContaining("nonce");
    }

    @Test
    @DisplayName("해시 계산이 앱과 같은 방식이어야 한다 — 소문자 16진수 64자")
    void sha256Hex_matchesExpectedFormat() {
        String hex = AppleIdentityVerifier.sha256Hex("abc");

        assertThat(hex).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("서버가 발급한 적 없는 nonce 는 거부한다")
    void unknownNonce_rejected() throws Exception {
        String token = token(signingKey, ISSUER, AUDIENCE, "sub", "never-issued", plusHours(1));

        assertThatThrownBy(() -> verify(token, "never-issued"))
                .isInstanceOf(OAuthVerificationException.class)
                .hasMessageContaining("만료");
    }

    @Test
    @DisplayName("같은 nonce 로 두 번째 로그인은 거부한다 — 재전송 방어의 핵심")
    void replayedNonce_rejected() throws Exception {
        challengePort.store("nonce-1");
        String token = token(signingKey, ISSUER, AUDIENCE, "sub", "nonce-1", plusHours(1));

        verify(token, "nonce-1");   // 첫 번째는 통과

        assertThatThrownBy(() -> verify(token, "nonce-1"))
                .isInstanceOf(OAuthVerificationException.class);
    }

    @Test
    @DisplayName("nonce 없이 오면 거부한다")
    void missingNonce_rejected() throws Exception {
        String token = token(signingKey, ISSUER, AUDIENCE, "sub", "n", plusHours(1));

        assertThatThrownBy(() -> verify(token, null))
                .isInstanceOf(OAuthVerificationException.class);
        assertThatThrownBy(() -> verify(token, "  "))
                .isInstanceOf(OAuthVerificationException.class);
    }

    @Test
    @DisplayName("토큰이 비어 있으면 거부한다")
    void blankToken_rejected() {
        assertThatThrownBy(() -> verify(null, "n")).isInstanceOf(OAuthVerificationException.class);
        assertThatThrownBy(() -> verify("", "n")).isInstanceOf(OAuthVerificationException.class);
    }

    @Test
    @DisplayName("허용 대상 앱 목록이 비면 검증기를 만들지 않는다 — 빈 목록은 전부 통과를 뜻한다")
    void emptyAudienceList_refusesToBuildDecoder() {
        assertThatThrownBy(() -> new AppleIdentityVerifier(
                new JwkDecoderFactory(), properties(List.of()), challengePort))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("전부 통과");
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private VerifiedIdentity verify(String token, String nonce) {
        return verifier.verify(new OAuthCredential(AuthProvider.APPLE, token, nonce, null));
    }

    private static Instant plusHours(long hours) {
        return Instant.now().plusSeconds(hours * 3600);
    }

    /**
     * 애플이 발급하는 토큰을 흉내 낸다.
     *
     * <p>{@code rawNonce} 를 그대로 담지 않고 SHA-256 해시를 담는다. 실제 애플 로그인이
     * 그렇게 동작하기 때문이다 — 앱이 해시를 SDK 에 넘기고 애플은 그 해시를 토큰에 박는다.
     */
    private static String token(RSAKey key, String issuer, String audience,
                                String subject, String rawNonce, Instant expiresAt) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(subject)
                .claim("nonce", rawNonce == null ? null : AppleIdentityVerifier.sha256Hex(rawNonce))
                .claim("email", subject + "@example.com")
                .issueTime(new Date())
                .expirationTime(Date.from(expiresAt))
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),
                claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    /** 발급한 값을 기억했다가 한 번만 내주는 가짜 저장소. */
    private static class StubChallengePort implements OAuthChallengePort {
        private final Set<String> issued = new HashSet<>();

        void store(String nonce) {
            issued.add(nonce);
        }

        @Override
        public String issue() {
            String nonce = "generated-" + issued.size();
            issued.add(nonce);
            return nonce;
        }

        @Override
        public boolean consume(String nonce) {
            return issued.remove(nonce);
        }
    }
}
