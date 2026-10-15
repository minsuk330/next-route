package watoo.grd.nextroute.infrastructure.adapter.in.api.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.auth.config.AdminProperties;
import watoo.grd.nextroute.application.auth.port.out.TokenGenerationPort;
import watoo.grd.nextroute.application.push.dto.RegisterPushTokenCommand;
import watoo.grd.nextroute.application.push.port.in.PushTokenUseCase;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;
import watoo.grd.nextroute.domain.push.entity.PushEnvironment;
import watoo.grd.nextroute.domain.push.entity.PushPlatform;
import watoo.grd.nextroute.domain.push.entity.PushProvider;
import watoo.grd.nextroute.global.error.GlobalExceptionHandler;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * push token API 의 계약과 보호 여부를 고정한다.
 *
 * <p>{@code SecurityConfig} 는 "적어 둔 경로만 보호"라 규칙을 빠뜨리면 익명에 열린다. 그리고
 * {@code /api/me} 규칙은 정확 경로 매처라 하위 경로를 덮지 않는다 — 익명 401 테스트가 그 가드다.
 *
 * <p>또 하나 못박는 것: token 은 개인식별에 준하므로 검증 실패 응답에 되비치면 안 된다
 * (REQUIREMENTS §6).
 */
@WebMvcTest(PushTokenController.class)
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class})
@DisplayName("push token API 계약")
class PushTokenControllerTest {

    private static final long USER_ID = 42L;
    private static final String TOKEN = "fcm-token-eXy3-abcdefghijklmnopqrstuvwxyz";

    @Autowired MockMvc mockMvc;
    @MockBean PushTokenUseCase pushTokenUseCase;
    @MockBean JwtProvider jwtProvider;
    @MockBean TokenGenerationPort tokenGenerationPort;
    @MockBean AdminProperties adminProperties;

    // ---------------------------------------------------------------- 보호

    @Test
    @DisplayName("401 — 인증 없이 남의 계정에 token 을 심을 수 없다")
    void registerRequiresAuth() throws Exception {
        mockMvc.perform(put("/api/me/push-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(TOKEN)))
                .andExpect(status().isUnauthorized());

        verify(pushTokenUseCase, never()).register(anyLong(), any());
    }

    @Test
    @DisplayName("401 — 인증 없이 남의 token 을 해제할 수 없다")
    void unregisterRequiresAuth() throws Exception {
        mockMvc.perform(delete("/api/me/push-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isUnauthorized());

        verify(pushTokenUseCase, never()).unregister(anyLong(), anyString());
    }

    // ---------------------------------------------------------------- 등록

    @Test
    @DisplayName("204 — 등록. 신원은 JWT 에서 오고 token 은 공백이 잘린다")
    void register() throws Exception {
        mockMvc.perform(put("/api/me/push-token")
                        .with(authentication(authUser(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("  " + TOKEN + "  ")))
                .andExpect(status().isNoContent());

        assertThat(capturedUserId()).isEqualTo(USER_ID);
        assertThat(capturedCommand().token()).isEqualTo(TOKEN);
        assertThat(capturedCommand().platform()).isEqualTo(PushPlatform.ANDROID);
    }

    @Test
    @DisplayName("capabilities 에 quietChannel 이 있으면 조용한 갱신 가능, 빠지면 불가, 생략하면 모름(#428)")
    void mapsQuietChannelCapability() throws Exception {
        mockMvc.perform(put("/api/me/push-token")
                        .with(authentication(authUser(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\",\"platform\":\"ANDROID\","
                                + "\"capabilities\":[\"quietChannel\",\"futureThing\"]}"))
                .andExpect(status().isNoContent());
        assertThat(capturedCommand().quietUpdateSupported()).isTrue();

        org.mockito.Mockito.clearInvocations(pushTokenUseCase);
        mockMvc.perform(put("/api/me/push-token")
                        .with(authentication(authUser(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\",\"platform\":\"ANDROID\",\"capabilities\":[]}"))
                .andExpect(status().isNoContent());
        assertThat(capturedCommand().quietUpdateSupported()).isFalse();

        org.mockito.Mockito.clearInvocations(pushTokenUseCase);
        mockMvc.perform(put("/api/me/push-token")
                        .with(authentication(authUser(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(TOKEN)))
                .andExpect(status().isNoContent());
        assertThat(capturedCommand().quietUpdateSupported())
                .as("옛 앱은 목록을 안 보낸다 — 이미 저장된 능력을 지우지 않게 null 로 넘긴다")
                .isNull();
    }

    @Test
    @DisplayName("provider·environment 를 생략하면 FCM·PRODUCTION 으로 채운다")
    void appliesDefaults() throws Exception {
        mockMvc.perform(put("/api/me/push-token")
                        .with(authentication(authUser(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","platform":"IOS"}
                                """.formatted(TOKEN)))
                .andExpect(status().isNoContent());

        assertThat(capturedCommand().provider()).isEqualTo(PushProvider.FCM);
        assertThat(capturedCommand().environment()).isEqualTo(PushEnvironment.PRODUCTION);
    }

    @Test
    @DisplayName("본문의 userId 는 무시한다 — 신원은 JWT 에서만 온다")
    void ignoresUserIdInBody() throws Exception {
        mockMvc.perform(put("/api/me/push-token")
                        .with(authentication(authUser(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":99,"token":"%s","platform":"ANDROID"}
                                """.formatted(TOKEN)))
                .andExpect(status().isNoContent());

        assertThat(capturedUserId()).isEqualTo(USER_ID);
    }

    // ---------------------------------------------------------------- 검증

    @Test
    @DisplayName("400 — 모르는 platform")
    void rejectsUnknownPlatform() throws Exception {
        mockMvc.perform(put("/api/me/push-token")
                        .with(authentication(authUser(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","platform":"WEB"}
                                """.formatted(TOKEN)))
                .andExpect(status().isBadRequest());

        verify(pushTokenUseCase, never()).register(anyLong(), any());
    }

    @Test
    @DisplayName("400 — 빈 token")
    void rejectsBlankToken() throws Exception {
        mockMvc.perform(put("/api/me/push-token")
                        .with(authentication(authUser(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("token"));
    }

    @Test
    @DisplayName("400 — 칼럼 상한(512자)을 넘는 token. 500 이 되면 안 된다")
    void rejectsOversizedToken() throws Exception {
        String oversized = "x".repeat(513);

        mockMvc.perform(put("/api/me/push-token")
                        .with(authentication(authUser(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(oversized)))
                .andExpect(status().isBadRequest());

        verify(pushTokenUseCase, never()).register(anyLong(), any());
    }

    @Test
    @DisplayName("검증 실패 응답에 token 문자열을 되비치지 않는다")
    void doesNotEchoToken() throws Exception {
        String rejected = "x".repeat(513);

        mockMvc.perform(put("/api/me/push-token")
                        .with(authentication(authUser(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(rejected)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString(rejected))));
    }

    // ---------------------------------------------------------------- 해제

    @Test
    @DisplayName("204 — 본문의 token 만 해제한다")
    void unregister() throws Exception {
        mockMvc.perform(delete("/api/me/push-token")
                        .with(authentication(authUser(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isNoContent());

        verify(pushTokenUseCase).unregister(USER_ID, TOKEN);
    }

    @Test
    @DisplayName("400 — 어느 token 을 뗄지 없으면 거절한다. 전체 해제로 넘어가지 않는다")
    void unregisterRequiresToken() throws Exception {
        mockMvc.perform(delete("/api/me/push-token")
                        .with(authentication(authUser(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(pushTokenUseCase, never()).unregister(anyLong(), anyString());
    }

    // ---------------------------------------------------------------- helpers

    private Authentication authUser(Long userId) {
        return new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private String body(String token) {
        return """
                {"token":"%s","platform":"ANDROID","provider":"FCM","environment":"PRODUCTION"}
                """.formatted(token);
    }

    private long capturedUserId() {
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(pushTokenUseCase).register(captor.capture(), any());
        return captor.getValue();
    }

    private RegisterPushTokenCommand capturedCommand() {
        ArgumentCaptor<RegisterPushTokenCommand> captor =
                ArgumentCaptor.forClass(RegisterPushTokenCommand.class);
        verify(pushTokenUseCase).register(anyLong(), captor.capture());
        return captor.getValue();
    }
}
