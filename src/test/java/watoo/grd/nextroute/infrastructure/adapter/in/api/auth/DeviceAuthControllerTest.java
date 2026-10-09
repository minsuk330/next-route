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
import watoo.grd.nextroute.application.auth.dto.OAuthLoginResult;
import watoo.grd.nextroute.application.auth.exception.DeviceAlreadyLinkedException;
import watoo.grd.nextroute.application.auth.exception.DeviceLinkLimitExceededException;
import watoo.grd.nextroute.application.auth.exception.DeviceLinkNotAllowedException;
import watoo.grd.nextroute.application.auth.exception.DeviceSignupRateLimitedException;
import watoo.grd.nextroute.application.auth.exception.InvalidDeviceSecretException;
import watoo.grd.nextroute.application.auth.port.in.DeviceLoginUseCase;
import watoo.grd.nextroute.application.auth.port.out.TokenGenerationPort;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;

import java.util.OptionalInt;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/auth/device} 의 공개 여부·Bearer 전달·출발지 IP·오류 코드 계약을 고정한다.
 */
@WebMvcTest(DeviceAuthController.class)
@Import({SecurityConfig.class, CorsConfig.class, AdminProperties.class})
class DeviceAuthControllerTest {

    private static final String SECRET = "s".repeat(43);
    private static final String BODY = "{\"deviceSecret\":\"" + SECRET + "\"}";

    @Autowired MockMvc mockMvc;

    @MockBean DeviceLoginUseCase deviceLoginUseCase;
    @MockBean JwtProvider jwtProvider;
    @MockBean TokenGenerationPort tokenGenerationPort;

    private static OAuthLoginResult result(long userId) {
        return new OAuthLoginResult("access", "refresh", userId, AuthProvider.DEVICE);
    }

    @Test
    @DisplayName("토큰 없이 부를 수 있다(공개) — 익명으로 위임하고 토큰 쌍을 돌려준다")
    void anonymous_returns200() throws Exception {
        given(deviceLoginUseCase.login(eq(SECRET), isNull(), anyString())).willReturn(result(7L));

        mockMvc.perform(post("/api/auth/device").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.refreshToken").value("refresh"))
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.provider").value("DEVICE"));
    }

    @Test
    @DisplayName("유효한 Bearer 면 그 사용자로 위임한다(기기 연결)")
    void validBearer_passesCurrentUser() throws Exception {
        given(jwtProvider.parseAccessPrincipal("good")).willReturn(new JwtProvider.TokenPrincipal(42L, 0));
        given(tokenGenerationPort.currentGeneration(42L)).willReturn(OptionalInt.of(0));
        given(deviceLoginUseCase.login(eq(SECRET), eq(42L), anyString())).willReturn(result(42L));

        mockMvc.perform(post("/api/auth/device").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42));
    }

    @Test
    @DisplayName("무효한 Bearer 는 익명으로 떨어진다 — 401 이 아니라 익명 위임")
    void invalidBearer_fallsBackToAnonymous() throws Exception {
        given(jwtProvider.parseAccessPrincipal("bad")).willThrow(new io.jsonwebtoken.JwtException("bad"));
        given(deviceLoginUseCase.login(eq(SECRET), isNull(), anyString())).willReturn(result(7L));

        mockMvc.perform(post("/api/auth/device").header("Authorization", "Bearer bad")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        then(deviceLoginUseCase).should().login(eq(SECRET), isNull(), anyString());
    }

    @Test
    @DisplayName("출발지 IP 는 X-Real-IP 를 쓰고 X-Forwarded-For 는 무시한다")
    void clientIp_prefersRealIp_ignoresForwardedFor() throws Exception {
        given(deviceLoginUseCase.login(any(), any(), any())).willReturn(result(7L));

        mockMvc.perform(post("/api/auth/device")
                        .header("X-Real-IP", "203.0.113.7")
                        .header("X-Forwarded-For", "198.51.100.1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        then(deviceLoginUseCase).should().login(SECRET, null, "203.0.113.7");
    }

    @Test
    @DisplayName("X-Real-IP 가 없으면 소켓 주소를 쓴다")
    void clientIp_fallsBackToRemoteAddr() throws Exception {
        given(deviceLoginUseCase.login(any(), any(), any())).willReturn(result(7L));

        mockMvc.perform(post("/api/auth/device")
                        .with(req -> { req.setRemoteAddr("192.0.2.9"); return req; })
                        .header("X-Forwarded-For", "198.51.100.1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        then(deviceLoginUseCase).should().login(SECRET, null, "192.0.2.9");
    }

    @Test
    @DisplayName("오류 코드: 400 형식 / 403 연결 불가 / 409 이미 연결·기기 수 초과 / 429 가입 한도")
    void errorCodes() throws Exception {
        expectError(new InvalidDeviceSecretException(), 400, "INVALID_DEVICE_SECRET");
        expectError(new DeviceLinkNotAllowedException(), 403, "DEVICE_LINK_NOT_ALLOWED");
        expectError(new DeviceAlreadyLinkedException(), 409, "DEVICE_ALREADY_LINKED");
        expectError(new DeviceLinkLimitExceededException(), 409, "DEVICE_LINK_LIMIT_EXCEEDED");
        expectError(new DeviceSignupRateLimitedException(), 429, "DEVICE_SIGNUP_RATE_LIMITED");
    }

    private void expectError(RuntimeException e, int status, String code) throws Exception {
        org.mockito.Mockito.reset(deviceLoginUseCase);
        given(deviceLoginUseCase.login(any(), any(), any())).willThrow(e);

        mockMvc.perform(post("/api/auth/device").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
    }
}
