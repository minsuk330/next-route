package watoo.grd.nextroute.common.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import watoo.grd.nextroute.application.auth.config.JwtProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 필터가 어떤 토큰을 인증으로 받아들이는지 고정한다.
 *
 * <p>가짜 provider 가 아니라 실제 {@link JwtProvider} 를 쓴다.
 * 토큰 종류 검사가 실제로 동작하는지가 이 테스트의 핵심이기 때문이다.
 */
class JwtAuthFilterTest {

    private JwtProvider jwtProvider;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret("test-secret-test-secret-test-secret-0123456789");
        props.setIssuer("nextroute-test");
        props.setAccessTtlSeconds(3600);
        props.setRefreshTtlSeconds(1209600);
        jwtProvider = new JwtProvider(props);
        filter = new JwtAuthFilter(jwtProvider);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Authentication authenticateWith(String headerValue) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (headerValue != null) {
            request.addHeader("Authorization", headerValue);
        }
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    @DisplayName("access 토큰이면 principal 에 userId 가 채워진다")
    void accessToken_authenticates() throws Exception {
        Authentication auth = authenticateWith("Bearer " + jwtProvider.createAccessToken(42L));

        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(42L);
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("refresh 토큰을 Bearer 로 보내면 인증되지 않는다 — 14일짜리 토큰이 API 를 열면 안 된다")
    void refreshToken_doesNotAuthenticate() throws Exception {
        Authentication auth = authenticateWith("Bearer " + jwtProvider.createRefreshToken(42L));

        assertThat(auth).isNull();
    }

    @Test
    @DisplayName("위조된 토큰은 인증되지 않는다")
    void tamperedToken_doesNotAuthenticate() throws Exception {
        String token = jwtProvider.createAccessToken(42L);
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThat(authenticateWith("Bearer " + tampered)).isNull();
    }

    @Test
    @DisplayName("헤더가 없거나 Bearer 가 아니면 익명으로 통과시킨다 — 공개 엔드포인트가 있기 때문")
    void missingOrNonBearerHeader_passesThroughAnonymously() throws Exception {
        assertThat(authenticateWith(null)).isNull();
        assertThat(authenticateWith("Basic abc")).isNull();
    }
}
