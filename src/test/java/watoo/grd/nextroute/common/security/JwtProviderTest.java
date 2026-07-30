package watoo.grd.nextroute.common.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.auth.config.JwtProperties;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-0123456789";
    private static final String ISSUER = "nextroute-test";

    private JwtProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtProvider(props(3600));
    }

    private static JwtProperties props(long accessTtlSeconds) {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        props.setIssuer(ISSUER);
        props.setAccessTtlSeconds(accessTtlSeconds);
        props.setRefreshTtlSeconds(86400);
        return props;
    }

    @Test
    void access_roundtrip() {
        String token = provider.createAccessToken(42L, 0);
        assertThat(provider.parseAccessPrincipal(token).userId()).isEqualTo(42L);
    }

    @Test
    void refresh_roundtrip() {
        String refresh = provider.createRefreshToken(7L, 0);
        assertThat(provider.parseRefreshPrincipal(refresh).userId()).isEqualTo(7L);
    }

    // ── 세대 번호 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("발급할 때 담은 세대 번호가 그대로 읽힌다 — 로그아웃 대조의 재료")
    void generation_roundtrip() {
        assertThat(provider.parseAccessPrincipal(provider.createAccessToken(42L, 3)).generation())
                .isEqualTo(3);
        assertThat(provider.parseRefreshPrincipal(provider.createRefreshToken(42L, 3)).generation())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("세대 번호가 없는 토큰은 0 으로 읽는다 — V34 이전 토큰이 배포 순간 튕기면 안 된다")
    void missingGenerationClaim_readsAsZero() {
        String legacy = legacyTokenWithoutGeneration(42L);

        JwtProvider.TokenPrincipal principal = provider.parseAccessPrincipal(legacy);

        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.generation()).isZero();
    }

    /** V34 이전에 발급하던 형태 — {@code gen} claim 이 없다. */
    private static String legacyTokenWithoutGeneration(long userId) {
        Date now = new Date();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(userId))
                .claim("type", "access")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 3600_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    // ── 토큰 종류 교차 사용 차단 ───────────────────────────────────────────────

    @Test
    @DisplayName("refresh 토큰은 access 로 쓸 수 없다 — 14일짜리 토큰이 API 를 열면 안 된다")
    void refreshToken_rejectedAsAccess() {
        String refresh = provider.createRefreshToken(7L, 0);

        assertThatThrownBy(() -> provider.parseAccessPrincipal(refresh))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("access");
    }

    @Test
    @DisplayName("access 토큰은 refresh 로 쓸 수 없다")
    void accessToken_rejectedAsRefresh() {
        String access = provider.createAccessToken(7L, 0);

        assertThatThrownBy(() -> provider.parseRefreshPrincipal(access))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("refresh");
    }

    // ── 서명·만료 ─────────────────────────────────────────────────────────────

    @Test
    void tampered_token_rejected() {
        String token = provider.createAccessToken(1L, 0);
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThatThrownBy(() -> provider.parseAccessPrincipal(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("세대 번호만 바꿔치기해도 서명이 깨진다 — 앱이 스스로 승격할 수 없다")
    void generationCannotBeForged() {
        String token = provider.createAccessToken(1L, 0);
        String forged = token.substring(0, token.indexOf('.') + 1)
                + "eyJnZW4iOjk5OX0" + token.substring(token.lastIndexOf('.'));

        assertThatThrownBy(() -> provider.parseAccessPrincipal(forged))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void expired_token_rejected() {
        JwtProvider expiredProvider = new JwtProvider(props(-1));
        String token = expiredProvider.createAccessToken(1L, 0);

        assertThatThrownBy(() -> expiredProvider.parseAccessPrincipal(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("다른 시크릿으로 서명한 토큰은 거부된다")
    void foreignSignature_rejected() {
        JwtProperties other = props(3600);
        other.setSecret("another-secret-another-secret-another-secret-987");
        String foreign = new JwtProvider(other).createAccessToken(1L, 0);

        assertThatThrownBy(() -> provider.parseAccessPrincipal(foreign))
                .isInstanceOf(JwtException.class);
    }
}
