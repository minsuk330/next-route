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
import watoo.grd.nextroute.application.journey.dto.JourneyLegView;
import watoo.grd.nextroute.application.journey.dto.JourneyRouteOptionView;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.exception.ActiveJourneyExistsException;
import watoo.grd.nextroute.application.journey.exception.JourneyCompileFailedException;
import watoo.grd.nextroute.application.journey.exception.SnapshotOwnerMismatchException;
import watoo.grd.nextroute.application.journey.exception.SnapshotUnavailableException;
import watoo.grd.nextroute.application.journey.port.in.GetActiveJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 여정 API 의 상태 코드 계약과 보호 여부를 고정한다.
 *
 * <p>{@code SecurityConfig} 는 "적어 둔 경로만 보호"라 규칙을 빠뜨리면 익명에 열린다. 그래서
 * 인증 없는 호출이 401 인지를 여기서 함께 본다.
 */
@WebMvcTest(JourneyController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class JourneyControllerTest {

    private static final String BODY = """
            {"searchSessionId":"sess-1","pathKey":"0"}
            """;

    @Autowired MockMvc mockMvc;
    @MockBean StartJourneyUseCase startJourneyUseCase;
    @MockBean GetActiveJourneyUseCase getActiveJourneyUseCase;
    @MockBean JwtProvider jwtProvider;                 // SecurityConfig 의존
    @MockBean TokenGenerationPort tokenGenerationPort; // SecurityConfig(JwtAuthFilter) 의존
    @MockBean AdminProperties adminProperties;         // SecurityConfig(AdminApiKeyFilter) 의존

    private Authentication authUser(Long userId) {
        return new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private JourneySnapshot snapshot(Long journeyId) {
        return new JourneySnapshot(journeyId, JourneyStatus.ACTIVE, 1L, 0L, 1, 0,
                Instant.parse("2026-07-31T09:03:00Z"), Instant.parse("2026-07-31T09:00:00Z"),
                List.of(new JourneyLegView(10L, 0, JourneyLegType.WAIT_BUS, JourneyLegState.ACTIVE,
                        "ST-A", "ST-A", "정류장A", "정류장A", null, null, null,
                        List.of(new JourneyRouteOptionView(100L, 0, 0, null, "BL-370", "370")))));
    }

    // ── 시작 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("시작하면 201 과 최소 상태를 준다")
    void start_returns201() throws Exception {
        given(startJourneyUseCase.start(eq(7L), any(StartJourneyCommand.class)))
                .willReturn(snapshot(1L));

        mockMvc.perform(post("/api/journeys")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.journeyId").value(1))
                .andExpect(jsonPath("$.commandVersion").value(1))
                .andExpect(jsonPath("$.evaluationVersion").value(0))
                .andExpect(jsonPath("$.routeRevision").value(1))
                .andExpect(jsonPath("$.legs[0].type").value("WAIT_BUS"))
                .andExpect(jsonPath("$.legs[0].state").value("ACTIVE"))
                .andExpect(jsonPath("$.legs[0].routeOptions[0].routeName").value("370"));
    }

    @Test
    @DisplayName("replace 는 쿼리 파라미터로 전달된다")
    void start_replaceFlag() throws Exception {
        given(startJourneyUseCase.start(eq(7L), any(StartJourneyCommand.class)))
                .willReturn(snapshot(1L));

        mockMvc.perform(post("/api/journeys?replace=true")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());

        verify(startJourneyUseCase).start(7L, new StartJourneyCommand("sess-1", "0", true));
    }

    @Test
    @DisplayName("기본값은 갈아타지 않는 것")
    void start_replaceDefaultsToFalse() throws Exception {
        given(startJourneyUseCase.start(eq(7L), any(StartJourneyCommand.class)))
                .willReturn(snapshot(1L));

        mockMvc.perform(post("/api/journeys")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());

        verify(startJourneyUseCase).start(7L, new StartJourneyCommand("sess-1", "0", false));
    }

    @Test
    @DisplayName("빈 요청은 400")
    void start_blankBody_returns400() throws Exception {
        mockMvc.perform(post("/api/journeys")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"searchSessionId\":\"\",\"pathKey\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── 오류 번역 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("진행 중인 여정이 있으면 409 와 그 여정을 함께 준다")
    void start_conflict_returns409WithCurrentJourney() throws Exception {
        willThrow(new ActiveJourneyExistsException(snapshot(42L)))
                .given(startJourneyUseCase).start(eq(7L), any(StartJourneyCommand.class));

        mockMvc.perform(post("/api/journeys")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_JOURNEY_EXISTS"))
                .andExpect(jsonPath("$.journey.journeyId").value(42));
    }

    @Test
    @DisplayName("스냅샷이 만료됐으면 410")
    void start_expired_returns410() throws Exception {
        willThrow(new SnapshotUnavailableException())
                .given(startJourneyUseCase).start(eq(7L), any(StartJourneyCommand.class));

        mockMvc.perform(post("/api/journeys")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SNAPSHOT_EXPIRED"));
    }

    @Test
    @DisplayName("남의 검색 결과면 403")
    void start_ownerMismatch_returns403() throws Exception {
        willThrow(new SnapshotOwnerMismatchException())
                .given(startJourneyUseCase).start(eq(7L), any(StartJourneyCommand.class));

        mockMvc.perform(post("/api/journeys")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SNAPSHOT_OWNER_MISMATCH"));
    }

    @Test
    @DisplayName("쪼갤 수 없는 경로는 422 — 만료와 구분한다")
    void start_compileFailed_returns422() throws Exception {
        willThrow(new JourneyCompileFailedException())
                .given(startJourneyUseCase).start(eq(7L), any(StartJourneyCommand.class));

        mockMvc.perform(post("/api/journeys")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("JOURNEY_COMPILE_FAILED"));
    }

    // ── 조회 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("진행 중인 여정이 있으면 200")
    void active_returns200() throws Exception {
        given(getActiveJourneyUseCase.getActive(7L)).willReturn(Optional.of(snapshot(5L)));

        mockMvc.perform(get("/api/journeys/active").with(authentication(authUser(7L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journeyId").value(5))
                .andExpect(jsonPath("$.serverTime").exists());
    }

    @Test
    @DisplayName("없으면 204 — 없는 것은 오류가 아니다")
    void active_returns204() throws Exception {
        given(getActiveJourneyUseCase.getActive(7L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/journeys/active").with(authentication(authUser(7L))))
                .andExpect(status().isNoContent());
    }

    // ── 보호 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("인증 없이는 시작할 수 없다")
    void start_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("인증 없이는 조회할 수 없다")
    void active_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/journeys/active"))
                .andExpect(status().isUnauthorized());
    }
}
