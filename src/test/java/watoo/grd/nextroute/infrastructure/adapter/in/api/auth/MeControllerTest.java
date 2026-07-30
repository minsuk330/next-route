package watoo.grd.nextroute.infrastructure.adapter.in.api.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.auth.config.AdminProperties;
import watoo.grd.nextroute.application.auth.dto.MeResult;
import watoo.grd.nextroute.application.auth.port.in.SessionUseCase;
import watoo.grd.nextroute.application.auth.port.out.TokenGenerationPort;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalInt;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MeController.class)
@Import({SecurityConfig.class, CorsConfig.class, AdminProperties.class})
class MeControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean SessionUseCase sessionUseCase;
    @MockBean JwtProvider jwtProvider;
    @MockBean TokenGenerationPort tokenGenerationPort;

    @Test
    @DisplayName("토큰 없이 부르면 401 — 남의 정보를 익명으로 볼 수 없다")
    void withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("유효한 토큰이면 내 정보를 돌려준다")
    void withValidToken_returnsMe() throws Exception {
        given(jwtProvider.parseAccessPrincipal("good"))
                .willReturn(new JwtProvider.TokenPrincipal(42L, 0));
        given(tokenGenerationPort.currentGeneration(42L)).willReturn(OptionalInt.of(0));
        given(sessionUseCase.me(42L)).willReturn(new MeResult(42L, "길동", "user@kakao.com",
                List.of(new MeResult.LinkedIdentity(AuthProvider.KAKAO,
                        LocalDateTime.of(2026, 7, 30, 15, 0)))));

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42))
                .andExpect(jsonPath("$.displayName").value("길동"))
                .andExpect(jsonPath("$.identities[0].provider").value("KAKAO"));
    }
}
