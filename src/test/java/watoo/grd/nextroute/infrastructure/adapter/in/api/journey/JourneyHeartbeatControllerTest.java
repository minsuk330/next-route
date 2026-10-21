package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.auth.config.AdminProperties;
import watoo.grd.nextroute.application.auth.port.out.TokenGenerationPort;
import watoo.grd.nextroute.application.journey.dto.JourneyEtaView;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.port.in.HeartbeatJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.JourneySnapshotEtaComposer;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.GlobalExceptionHandler;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * heartbeat API 의 상태 코드 계약과 보호 여부를 고정한다.
 *
 * <p>{@code SecurityConfig} 는 "적어 둔 경로만 보호"라 규칙을 빠뜨리면 익명에 열린다. 남이
 * 여정 lease 를 연장할 수 있으면 만료 판정 자체가 무의미해지므로 401 을 함께 못박는다.
 */
@WebMvcTest(JourneyHeartbeatController.class)
@Import({SecurityConfig.class, CorsConfig.class,
        GlobalExceptionHandler.class, JourneyControllerAdvice.class, JourneySnapshotEtaAttacher.class})
@DisplayName("heartbeat API 계약")
class JourneyHeartbeatControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean HeartbeatJourneyUseCase heartbeatJourneyUseCase;
    @MockBean JourneySnapshotEtaComposer etaComposer;
    @MockBean JwtProvider jwtProvider;
    @MockBean TokenGenerationPort tokenGenerationPort;
    @MockBean AdminProperties adminProperties;

    @Test
    @DisplayName("200 — 최신 스냅샷에 조회와 같은 예측을 얹는다(#470). 앱 복귀 직후 이 응답으로 갈아끼운다")
    void heartbeat() throws Exception {
        given(heartbeatJourneyUseCase.heartbeat(eq(1L), eq(7L))).willReturn(snapshot());
        given(etaComposer.compose(eq(1L), any())).willAnswer(call -> {
            JourneySnapshot snapshot = call.getArgument(1);
            return snapshot.withEta(new JourneyEtaView(null, null, null,
                    Instant.parse("2026-08-04T09:12:00Z"), null, null), snapshot.recommendedPollSeconds());
        });

        mockMvc.perform(post("/api/journeys/7/heartbeat").with(authentication(authUser(1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journeyId").value(7))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.guidanceLeaseUntil").value("2026-08-04T09:03:00Z"))
                .andExpect(jsonPath("$.eta.journeyEta").value("2026-08-04T09:12:00Z"));
    }

    @Test
    @DisplayName("200 — 예측 합성이 실패해도 heartbeat 는 성공한다. lease 는 이미 연장됐다")
    void heartbeatSurvivesEtaFailure() throws Exception {
        given(heartbeatJourneyUseCase.heartbeat(eq(1L), eq(7L))).willReturn(snapshot());
        given(etaComposer.compose(any(), any())).willThrow(new IllegalStateException("db down"));

        mockMvc.perform(post("/api/journeys/7/heartbeat").with(authentication(authUser(1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guidanceLeaseUntil").value("2026-08-04T09:03:00Z"))
                .andExpect(jsonPath("$.eta").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("401 — 인증 없이는 남의 여정 lease 를 연장할 수 없다")
    void requiresAuth() throws Exception {
        mockMvc.perform(post("/api/journeys/7/heartbeat"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("404 — 없는 여정이거나 남의 여정")
    void notFound() throws Exception {
        willThrow(new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND))
                .given(heartbeatJourneyUseCase).heartbeat(eq(1L), eq(7L));

        mockMvc.perform(post("/api/journeys/7/heartbeat").with(authentication(authUser(1L))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("409 — 끝난 여정은 heartbeat 로 되살아나지 않는다")
    void notActive() throws Exception {
        willThrow(new BusinessException(JourneyErrorCode.JOURNEY_NOT_ACTIVE))
                .given(heartbeatJourneyUseCase).heartbeat(eq(1L), eq(7L));

        mockMvc.perform(post("/api/journeys/7/heartbeat").with(authentication(authUser(1L))))
                .andExpect(status().isConflict());
    }

    private Authentication authUser(Long userId) {
        return new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private JourneySnapshot snapshot() {
        return JourneySnapshot.of(7L, JourneyStatus.ACTIVE, JourneyKind.NAVIGATION, 1L, 0L, 1, 0,
                Instant.parse("2026-08-04T09:03:00Z"), null,
                Instant.parse("2026-08-04T09:00:00Z"), List.of());
    }
}
