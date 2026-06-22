package watoo.grd.nextroute.infrastructure.adapter.in.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.auth.dto.TokenResult;
import watoo.grd.nextroute.application.auth.port.in.TossLoginUseCase;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SecurityConfig 규칙 + Security 통합 CORS 검증: 필터 체인 적용(addFilters 기본 true). */
@WebMvcTest(TossAuthController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class TossAuthSecurityTest {

    @Autowired MockMvc mockMvc;

    @MockBean TossLoginUseCase tossLoginUseCase;
    @MockBean JwtProvider jwtProvider;

    @Test
    void logout_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/toss/logout"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reissue_isPermitted_withoutToken() throws Exception {
        given(tossLoginUseCase.reissue("ref")).willReturn(new TokenResult("a", "r"));

        mockMvc.perform(post("/api/auth/toss/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"ref\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void logout_withValidToken_returns204() throws Exception {
        given(jwtProvider.parseUserId("good")).willReturn(55L);

        mockMvc.perform(post("/api/auth/toss/logout")
                        .header("Authorization", "Bearer good"))
                .andExpect(status().isNoContent());
    }

    @Test
    void preflight_handledBySecurityCors() throws Exception {
        mockMvc.perform(options("/api/auth/toss/login")
                        .header("Origin", "https://example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://example.com"));
    }
}
