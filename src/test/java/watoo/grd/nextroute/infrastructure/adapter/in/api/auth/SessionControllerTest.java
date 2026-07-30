package watoo.grd.nextroute.infrastructure.adapter.in.api.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.auth.config.AdminProperties;
import watoo.grd.nextroute.application.auth.dto.TokenResult;
import watoo.grd.nextroute.application.auth.exception.SessionException;
import watoo.grd.nextroute.application.auth.port.in.SessionUseCase;
import watoo.grd.nextroute.application.auth.port.out.TokenGenerationPort;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;

import java.util.OptionalInt;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 어떤 경로가 인증을 요구하는지 고정한다.
 *
 * <p>SecurityConfig 는 "적어 둔 것만 보호"하는 설정이라 규칙을 빠뜨리면 익명에 열린다.
 * 로그아웃이 그렇게 열리면 남의 세션을 끊을 수 있다.
 */
@WebMvcTest(SessionController.class)
@Import({SecurityConfig.class, CorsConfig.class, AdminProperties.class})
class SessionControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean SessionUseCase sessionUseCase;
    @MockBean JwtProvider jwtProvider;
    @MockBean TokenGenerationPort tokenGenerationPort;

    /** 유효한 access 토큰인 척한다. 실제 서명 검증은 JwtProviderTest 가 맡는다. */
    private void authenticated(long userId) {
        given(jwtProvider.parseAccessPrincipal("good"))
                .willReturn(new JwtProvider.TokenPrincipal(userId, 0));
        given(tokenGenerationPort.currentGeneration(userId)).willReturn(OptionalInt.of(0));
    }

    // ── 로그아웃 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("토큰 없이 로그아웃하면 401 이고 서비스는 불리지 않는다")
    void logout_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized());

        verify(sessionUseCase, never()).logout(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("유효한 토큰이면 204 이고 토큰 주인의 세션을 끊는다")
    void logout_withValidToken_returns204() throws Exception {
        authenticated(55L);

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer good"))
                .andExpect(status().isNoContent());

        verify(sessionUseCase).logout(55L);
    }

    @Test
    @DisplayName("이미 폐기된 세대의 토큰으로는 로그아웃도 못 한다")
    void logout_withStaleToken_returns401() throws Exception {
        given(jwtProvider.parseAccessPrincipal("stale"))
                .willReturn(new JwtProvider.TokenPrincipal(55L, 0));
        given(tokenGenerationPort.currentGeneration(55L)).willReturn(OptionalInt.of(1));

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer stale"))
                .andExpect(status().isUnauthorized());
    }

    // ── 재발급 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("재발급은 토큰 없이 부를 수 있다 — access 가 만료된 뒤 쓰는 경로다")
    void reissue_isPermitted_withoutToken() throws Exception {
        given(sessionUseCase.reissue("ref")).willReturn(new TokenResult("a", "r"));

        mockMvc.perform(post("/api/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"ref\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("a"))
                .andExpect(jsonPath("$.refreshToken").value("r"));
    }

    @Test
    @DisplayName("폐기된 refresh 는 401 이고 이유를 알려주지 않는다")
    void reissue_rejected_returns401WithoutDetail() throws Exception {
        willThrow(new SessionException("이미 로그아웃된 세션이다"))
                .given(sessionUseCase).reissue("old");

        mockMvc.perform(post("/api/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
    }

    @Test
    @DisplayName("refresh 토큰이 비어 있으면 400")
    void reissue_blankToken_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
