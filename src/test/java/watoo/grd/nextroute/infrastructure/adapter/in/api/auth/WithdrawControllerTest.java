package watoo.grd.nextroute.infrastructure.adapter.in.api.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.auth.config.AdminProperties;
import watoo.grd.nextroute.application.auth.port.in.WithdrawUseCase;
import watoo.grd.nextroute.application.auth.port.out.TokenGenerationPort;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;

import java.util.OptionalInt;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code DELETE /api/auth/me} 의 상태 코드 계약과 보호 여부를 고정한다.
 *
 * <p>{@code SecurityConfig} 는 "적어 둔 경로만 보호"라 규칙을 빠뜨리면 익명에 열린다. 익명이
 * 남의 계정을 탈퇴시킬 수 있으면 안 되므로 401 을 함께 못박는다.
 */
@WebMvcTest(WithdrawController.class)
@Import({SecurityConfig.class, CorsConfig.class, AdminProperties.class})
class WithdrawControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean WithdrawUseCase withdrawUseCase;
    @MockBean JwtProvider jwtProvider;
    @MockBean TokenGenerationPort tokenGenerationPort;

    @Test
    @DisplayName("토큰 없이 부르면 401 — 익명이 남의 계정을 탈퇴시킬 수 없다")
    void withoutToken_returns401() throws Exception {
        mockMvc.perform(delete("/api/auth/me")).andExpect(status().isUnauthorized());
        then(withdrawUseCase).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("유효한 토큰이면 204 를 주고 인증 사용자로 탈퇴를 위임한다")
    void withValidToken_withdrawsAndReturns204() throws Exception {
        given(jwtProvider.parseAccessPrincipal("good"))
                .willReturn(new JwtProvider.TokenPrincipal(42L, 0));
        given(tokenGenerationPort.currentGeneration(42L)).willReturn(OptionalInt.of(0));

        mockMvc.perform(delete("/api/auth/me").header("Authorization", "Bearer good"))
                .andExpect(status().isNoContent());

        then(withdrawUseCase).should().withdraw(42L);
    }
}
