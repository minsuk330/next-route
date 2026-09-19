package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import watoo.grd.nextroute.application.journey.dto.JourneyProgressOutcome;
import watoo.grd.nextroute.application.journey.exception.ProgressTimestampConflictException;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyProgressUseCase;
import watoo.grd.nextroute.application.journey.port.in.JourneyQueryUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.JourneySnapshotEtaComposer;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;
import watoo.grd.nextroute.global.error.GlobalExceptionHandler;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * progress API 의 상태 코드 계약과 보호 여부를 고정한다.
 *
 * <p>{@code SecurityConfig} 는 "적어 둔 경로만 보호"라 규칙을 빠뜨리면 익명에 열린다.
 */
@WebMvcTest(JourneyController.class)
@Import({SecurityConfig.class, CorsConfig.class,
        GlobalExceptionHandler.class, JourneyControllerAdvice.class})
@DisplayName("progress API 계약")
class JourneyProgressApiTest {

    private static final String BODY = """
            {"progressId":"3f1d6b64-0f1f-4a1a-9a6a-6f3a5a0d1111",
             "routeRevision":1,"legIndex":0,"geometrySegmentIndex":3,
             "segmentProgress":0.62,"legProgress":0.71,"sectionId":"sec-1",
             "remainingDistanceMeters":1840,"remainingDurationSeconds":420,
             "remainingStops":3,"observedAt":"2026-08-04T09:00:00Z","quality":"GOOD"}
            """;

    @Autowired MockMvc mockMvc;
    @MockBean ApplyJourneyProgressUseCase applyJourneyProgressUseCase;
    @MockBean StartJourneyUseCase startJourneyUseCase;
    @MockBean JourneyQueryUseCase getActiveJourneyUseCase;
    @MockBean ApplyJourneyEventUseCase applyJourneyEventUseCase;
    @MockBean JourneySnapshotEtaComposer etaComposer;
    @MockBean JwtProvider jwtProvider;
    @MockBean TokenGenerationPort tokenGenerationPort;
    @MockBean AdminProperties adminProperties;

    @Test
    @DisplayName("200 — 반영되면 결과와 최신 상태를 함께 준다")
    void applied() throws Exception {
        given(applyJourneyProgressUseCase.apply(any(), any(), any())).willReturn(
                new JourneyProgressOutcome(JourneyProgressOutcome.Result.APPLIED, null));

        mockMvc.perform(put("/api/journeys/7/progress")
                        .with(authentication(authUser(1L))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED"));
    }

    @Test
    @DisplayName("200 — 재전송도 성공으로 본다. 앱이 보기에 반영된 사실은 같다")
    void duplicateIsOk() throws Exception {
        given(applyJourneyProgressUseCase.apply(any(), any(), any())).willReturn(
                new JourneyProgressOutcome(JourneyProgressOutcome.Result.DUPLICATE, null));

        mockMvc.perform(put("/api/journeys/7/progress")
                        .with(authentication(authUser(1L))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DUPLICATE"));
    }

    @Test
    @DisplayName("409 — 같은 관측 시각 충돌은 그 시각을 details 로 준다")
    void timestampConflict() throws Exception {
        given(applyJourneyProgressUseCase.apply(any(), any(), any()))
                .willThrow(new ProgressTimestampConflictException(
                        Instant.parse("2026-08-04T09:00:00Z")));

        mockMvc.perform(put("/api/journeys/7/progress")
                        .with(authentication(authUser(1L))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROGRESS_TIMESTAMP_CONFLICT"));
    }

    @Test
    @DisplayName("400 — progressId 나 observedAt 이 없으면 받지 않는다")
    void rejectsMissingRequiredFields() throws Exception {
        mockMvc.perform(put("/api/journeys/7/progress")
                        .with(authentication(authUser(1L))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeRevision":1,"legIndex":0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("401 — 인증 없이 부르면 막는다")
    void requiresAuth() throws Exception {
        mockMvc.perform(put("/api/journeys/7/progress").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    private Authentication authUser(Long userId) {
        return new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
