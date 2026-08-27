package watoo.grd.nextroute.application.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.auth.config.JwtProperties;
import watoo.grd.nextroute.application.auth.dto.TokenResult;
import watoo.grd.nextroute.application.auth.port.in.SessionUseCase;
import watoo.grd.nextroute.common.security.JwtProvider;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그아웃이 실제로 토큰을 죽이는지 실제 DB 로 확인한다.
 *
 * <p>단위 테스트로는 여기까지 못 온다. 세대 번호는 {@code users} 에 저장되고, 토큰에 복사돼
 * 나가고, 요청마다 필터가 다시 읽어 대조한다 — 이 세 곳이 맞물려야 로그아웃이 성립한다.
 * 하나라도 어긋나면 "로그아웃했는데 토큰이 계속 통하는" 상태가 되고, 그건 조용히 지나간다.
 *
 * <p>트랜잭션을 걸지 않는다. 필터가 보는 값은 <b>커밋된</b> 값이어야 하기 때문이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        // relay 기본값(enabled=true)+sender 기본값(noop)은 부팅 검증에 걸린다. 이 테스트와
        // 무관한 배경 발송기라 꺼서 컨텍스트가 뜨게 한다.
        "journey.notification.relay.enabled=false"
})
class TokenGenerationIT {

    /** 인증이 필요한 아무 경로. 보호되는지만 보면 되므로 응답 내용은 상관없다. */
    private static final String PROTECTED = "/api/me";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JwtProvider jwtProvider;
    @Autowired JwtProperties jwtProperties;
    @Autowired SessionUseCase sessionUseCase;

    private long userId;

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (created_at, updated_at, token_generation)
                VALUES (now(), now(), 0) RETURNING id
                """, Long.class);
    }

    private int storedGeneration() {
        return jdbcTemplate.queryForObject(
                "SELECT token_generation FROM users WHERE id = ?", Integer.class, userId);
    }

    private String accessTokenNow() {
        return jwtProvider.createAccessToken(userId, storedGeneration());
    }

    private int callProtectedWith(String accessToken) throws Exception {
        return mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + accessToken))
                .andReturn().getResponse().getStatus();
    }

    // ── 로그아웃 전후 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("로그아웃 전에는 통하고, 로그아웃하면 같은 토큰이 막힌다")
    void accessToken_diesOnLogout() throws Exception {
        String access = accessTokenNow();
        assertThat(callProtectedWith(access)).isEqualTo(200);

        sessionUseCase.logout(userId);

        assertThat(callProtectedWith(access)).isEqualTo(401);
        assertThat(storedGeneration()).isEqualTo(1);
    }

    @Test
    @DisplayName("로그아웃하면 refresh 로 재발급도 막힌다 — 안 막으면 로그아웃이 1시간짜리가 된다")
    void refreshToken_diesOnLogout() {
        String refresh = jwtProvider.createRefreshToken(userId, storedGeneration());

        sessionUseCase.logout(userId);

        assertThatThrownBy(() -> sessionUseCase.reissue(refresh))
                .isInstanceOf(watoo.grd.nextroute.application.auth.exception.SessionException.class);
    }

    @Test
    @DisplayName("로그아웃 뒤 재로그인하면 새 토큰은 통한다")
    void newTokenAfterLogout_works() throws Exception {
        sessionUseCase.logout(userId);

        assertThat(callProtectedWith(accessTokenNow())).isEqualTo(200);
    }

    @Test
    @DisplayName("재발급받은 토큰도 그대로 통한다")
    void reissuedToken_works() throws Exception {
        TokenResult reissued = sessionUseCase.reissue(
                jwtProvider.createRefreshToken(userId, storedGeneration()));

        assertThat(callProtectedWith(reissued.accessToken())).isEqualTo(200);
    }

    @Test
    @DisplayName("두 번 로그아웃해도 문제없다 — 앱이 재시도할 수 있다")
    void logoutTwice_isSafe() throws Exception {
        sessionUseCase.logout(userId);
        sessionUseCase.logout(userId);

        assertThat(storedGeneration()).isEqualTo(2);
        assertThat(callProtectedWith(accessTokenNow())).isEqualTo(200);
    }

    // ── 이전 버전 토큰 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("V34 이전 토큰은 그대로 통한다 — 배포 순간 강제 로그아웃이 없어야 한다")
    void legacyTokenWithoutGeneration_stillWorks() throws Exception {
        assertThat(callProtectedWith(legacyToken())).isEqualTo(200);
    }

    @Test
    @DisplayName("V34 이전 토큰도 한 번 로그아웃하면 막힌다")
    void legacyTokenWithoutGeneration_diesOnLogout() throws Exception {
        String legacy = legacyToken();

        sessionUseCase.logout(userId);

        assertThat(callProtectedWith(legacy)).isEqualTo(401);
    }

    /** V34 이전에 발급하던 형태 — {@code gen} claim 이 없다. */
    private String legacyToken() {
        Date now = new Date();
        return Jwts.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(String.valueOf(userId))
                .claim("type", "access")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 3600_000))
                .signWith(Keys.hmacShaKeyFor(
                        jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    // ── 사라진 사용자 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("없는 사용자의 토큰은 서명이 멀쩡해도 막힌다")
    void tokenForUnknownUser_rejected() throws Exception {
        String orphan = jwtProvider.createAccessToken(999_999_999L, 0);

        assertThat(callProtectedWith(orphan)).isEqualTo(401);
    }

    // ── 탈퇴한 사용자 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("탈퇴하면 세대가 맞는 access 도 막힌다 — 행은 남지만 없는 사용자로 친다")
    void accessToken_diesOnWithdrawal() throws Exception {
        String access = accessTokenNow();
        assertThat(callProtectedWith(access)).isEqualTo(200);

        softDeleteUser();

        assertThat(callProtectedWith(access)).isEqualTo(401);
    }

    @Test
    @DisplayName("탈퇴하면 세대가 맞는 refresh 도 재발급이 막힌다")
    void refreshToken_diesOnWithdrawal() {
        String refresh = jwtProvider.createRefreshToken(userId, storedGeneration());

        softDeleteUser();

        assertThatThrownBy(() -> sessionUseCase.reissue(refresh))
                .isInstanceOf(watoo.grd.nextroute.application.auth.exception.SessionException.class);
    }

    /** 탈퇴가 하는 일 중 이 테스트에 필요한 만큼 — {@code deleted_at} 만 찍는다. 세대는 건드리지 않는다. */
    private void softDeleteUser() {
        jdbcTemplate.update("UPDATE users SET deleted_at = now() WHERE id = ?", userId);
    }

    @Test
    @DisplayName("refresh 토큰을 Bearer 로 보내면 막힌다")
    void refreshTokenAsBearer_rejected() throws Exception {
        String refresh = jwtProvider.createRefreshToken(userId, storedGeneration());

        assertThat(callProtectedWith(refresh)).isEqualTo(401);
    }

    @Test
    @DisplayName("로그아웃 경로 자체도 인증을 요구한다")
    void logoutEndpoint_requiresAuth() throws Exception {
        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized());
    }
}
