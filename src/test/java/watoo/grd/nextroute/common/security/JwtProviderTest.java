package watoo.grd.nextroute.common.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.auth.config.JwtProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {

    private JwtProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtProvider(props(3600));
    }

    private static JwtProperties props(long accessTtlSeconds) {
        JwtProperties props = new JwtProperties();
        props.setSecret("test-secret-test-secret-test-secret-0123456789");
        props.setIssuer("nextroute-test");
        props.setAccessTtlSeconds(accessTtlSeconds);
        props.setRefreshTtlSeconds(86400);
        return props;
    }

    @Test
    void access_roundtrip() {
        String token = provider.createAccessToken(42L);
        assertThat(provider.parseAccessUserId(token)).isEqualTo(42L);
    }

    @Test
    void refresh_roundtrip() {
        String refresh = provider.createRefreshToken(7L);
        assertThat(provider.parseRefreshUserId(refresh)).isEqualTo(7L);
    }

    // ── 토큰 종류 교차 사용 차단 ───────────────────────────────────────────────

    @Test
    @DisplayName("refresh 토큰은 access 로 쓸 수 없다 — 14일짜리 토큰이 API 를 열면 안 된다")
    void refreshToken_rejectedAsAccess() {
        String refresh = provider.createRefreshToken(7L);

        assertThatThrownBy(() -> provider.parseAccessUserId(refresh))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("access");
    }

    @Test
    @DisplayName("access 토큰은 refresh 로 쓸 수 없다")
    void accessToken_rejectedAsRefresh() {
        String access = provider.createAccessToken(7L);

        assertThatThrownBy(() -> provider.parseRefreshUserId(access))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("refresh");
    }

    // ── 서명·만료 ─────────────────────────────────────────────────────────────

    @Test
    void tampered_token_rejected() {
        String token = provider.createAccessToken(1L);
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThatThrownBy(() -> provider.parseAccessUserId(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void expired_token_rejected() {
        JwtProvider expiredProvider = new JwtProvider(props(-1));
        String token = expiredProvider.createAccessToken(1L);

        assertThatThrownBy(() -> expiredProvider.parseAccessUserId(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("다른 시크릿으로 서명한 토큰은 거부된다")
    void foreignSignature_rejected() {
        JwtProperties other = props(3600);
        other.setSecret("another-secret-another-secret-another-secret-987");
        String foreign = new JwtProvider(other).createAccessToken(1L);

        assertThatThrownBy(() -> provider.parseAccessUserId(foreign))
                .isInstanceOf(JwtException.class);
    }
}
