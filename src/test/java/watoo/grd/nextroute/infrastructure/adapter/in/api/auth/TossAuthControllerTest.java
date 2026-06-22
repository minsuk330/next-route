package watoo.grd.nextroute.infrastructure.adapter.in.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.auth.dto.LoginResult;
import watoo.grd.nextroute.application.auth.dto.TokenResult;
import watoo.grd.nextroute.application.auth.exception.TossLoginException;
import watoo.grd.nextroute.application.auth.port.in.TossLoginUseCase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TossAuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class TossAuthControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean TossLoginUseCase tossLoginUseCase;

    @Test
    void login_returnsTokens() throws Exception {
        given(tossLoginUseCase.login(eq("code"), eq("DEFAULT")))
                .willReturn(new LoginResult("acc", "ref", 1L, 999L, "홍길동"));

        mockMvc.perform(post("/api/auth/toss/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"code\",\"referrer\":\"DEFAULT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("acc"))
                .andExpect(jsonPath("$.refreshToken").value("ref"))
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.tossUserKey").value(999));
    }

    @Test
    void login_missingCode_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/toss/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"referrer\":\"DEFAULT\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_invalidGrant_returns401() throws Exception {
        given(tossLoginUseCase.login(any(), any()))
                .willThrow(new TossLoginException("expired", true));

        mockMvc.perform(post("/api/auth/toss/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"x\",\"referrer\":\"DEFAULT\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reissue_returnsNewTokens() throws Exception {
        given(tossLoginUseCase.reissue("ref")).willReturn(new TokenResult("a2", "r2"));

        mockMvc.perform(post("/api/auth/toss/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"ref\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("a2"));
    }
}
