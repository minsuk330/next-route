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
import watoo.grd.nextroute.application.auth.port.out.TokenGenerationPort;

import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 필터가 어떤 토큰을 인증으로 받아들이는지 고정한다.
 *
 * <p>가짜 provider 가 아니라 실제 {@link JwtProvider} 를 쓴다.
 * 토큰 종류 검사와 세대 번호 대조가 실제로 동작하는지가 이 테스트의 핵심이기 때문이다.
 *
 * <p>세대 번호 저장소는 손으로 만든 가짜다. 값 하나를 바꿔 가며 "로그아웃 이후"를 흉내 낸다.
 */
class JwtAuthFilterTest {

    private JwtProvider jwtProvider;
    private JwtAuthFilter filter;

    /** 지금 사용자에게 붙어 있는 세대 번호. {@code null} 이면 사용자가 없다는 뜻. */
    private Integer currentGeneration;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret("test-secret-test-secret-test-secret-0123456789");
        props.setIssuer("nextroute-test");
        props.setAccessTtlSeconds(3600);
        props.setRefreshTtlSeconds(1209600);
        jwtProvider = new JwtProvider(props);

        currentGeneration = 0;
        TokenGenerationPort tokenGenerationPort = userId ->
                currentGeneration == null ? OptionalInt.empty() : OptionalInt.of(currentGeneration);

        filter = new JwtAuthFilter(jwtProvider, tokenGenerationPort);
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
        Authentication auth = authenticateWith("Bearer " + jwtProvider.createAccessToken(42L, 0));

        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(42L);
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }

    // ── 세대 번호 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("로그아웃 뒤의 토큰은 인증되지 않는다 — 이 검사가 로그아웃을 실제로 동작하게 한다")
    void staleGeneration_doesNotAuthenticate() throws Exception {
        String beforeLogout = jwtProvider.createAccessToken(42L, 0);

        currentGeneration = 1;   // 로그아웃이 번호를 올렸다

        assertThat(authenticateWith("Bearer " + beforeLogout)).isNull();
    }

    @Test
    @DisplayName("로그아웃 뒤 재로그인한 토큰은 통과한다")
    void newGenerationAfterLogout_authenticates() throws Exception {
        currentGeneration = 1;

        Authentication auth = authenticateWith("Bearer " + jwtProvider.createAccessToken(42L, 1));

        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(42L);
    }

    @Test
    @DisplayName("사용자가 없으면 서명이 멀쩡해도 인증되지 않는다")
    void unknownUser_doesNotAuthenticate() throws Exception {
        String token = jwtProvider.createAccessToken(42L, 0);

        currentGeneration = null;

        assertThat(authenticateWith("Bearer " + token)).isNull();
    }

    // ── 토큰 종류·위조 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("refresh 토큰을 Bearer 로 보내면 인증되지 않는다 — 14일짜리 토큰이 API 를 열면 안 된다")
    void refreshToken_doesNotAuthenticate() throws Exception {
        Authentication auth = authenticateWith("Bearer " + jwtProvider.createRefreshToken(42L, 0));

        assertThat(auth).isNull();
    }

    @Test
    @DisplayName("위조된 토큰은 인증되지 않는다")
    void tamperedToken_doesNotAuthenticate() throws Exception {
        String token = jwtProvider.createAccessToken(42L, 0);
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
