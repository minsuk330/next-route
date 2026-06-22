package watoo.grd.nextroute.common.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.auth.config.JwtProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {

    private JwtProvider provider;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret("test-secret-test-secret-test-secret-0123456789");
        props.setIssuer("nextroute-test");
        props.setAccessTtlSeconds(3600);
        props.setRefreshTtlSeconds(86400);
        provider = new JwtProvider(props);
    }

    @Test
    void access_roundtrip() {
        String token = provider.createAccessToken(42L);
        assertThat(provider.parseUserId(token)).isEqualTo(42L);
    }

    @Test
    void refresh_roundtrip_andTypeCheck() {
        String refresh = provider.createRefreshToken(7L);
        assertThat(provider.parseRefreshUserId(refresh)).isEqualTo(7L);

        // access 토큰을 refresh로 쓰면 거부
        String access = provider.createAccessToken(7L);
        assertThatThrownBy(() -> provider.parseRefreshUserId(access))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void tampered_token_rejected() {
        String token = provider.createAccessToken(1L);
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThatThrownBy(() -> provider.parseUserId(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void expired_token_rejected() {
        JwtProperties props = new JwtProperties();
        props.setSecret("test-secret-test-secret-test-secret-0123456789");
        props.setIssuer("nextroute-test");
        props.setAccessTtlSeconds(-1);
        JwtProvider expiredProvider = new JwtProvider(props);

        String token = expiredProvider.createAccessToken(1L);
        assertThatThrownBy(() -> expiredProvider.parseUserId(token))
                .isInstanceOf(JwtException.class);
    }
}
