package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
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
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyEtaView;
import watoo.grd.nextroute.application.journey.dto.JourneyEventOutcome;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.SubwayGuidance;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPhase;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.exception.ActiveJourneyExistsException;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.JourneyQueryUseCase;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyProgressUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.JourneySnapshotEtaComposer;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.CommonErrorCode;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
@Import({SecurityConfig.class, CorsConfig.class, JourneySnapshotEtaAttacher.class})
class JourneyControllerTest {

    private static final String BODY = """
            {"searchSessionId":"sess-1","pathKey":"0"}
            """;

    @Autowired MockMvc mockMvc;
    @MockBean StartJourneyUseCase startJourneyUseCase;
    @MockBean JourneyQueryUseCase getActiveJourneyUseCase;
    @MockBean ApplyJourneyEventUseCase applyJourneyEventUseCase;
    @MockBean ApplyJourneyProgressUseCase applyJourneyProgressUseCase;
    @MockBean JourneySnapshotEtaComposer etaComposer;
    @MockBean JwtProvider jwtProvider;                 // SecurityConfig 의존
    @MockBean TokenGenerationPort tokenGenerationPort; // SecurityConfig(JwtAuthFilter) 의존
    @MockBean AdminProperties adminProperties;         // SecurityConfig(AdminApiKeyFilter) 의존

    @BeforeEach
    void stubComposer() {
        // 예측 합성은 별도 테스트가 본다. 여기서는 통과시켜 기본 스냅샷 계약만 확인한다.
        given(etaComposer.compose(any(), any())).willAnswer(call -> call.getArgument(1));
    }

    private Authentication authUser(Long userId) {
        return new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private JourneySnapshot snapshot(Long journeyId) {
        return JourneySnapshot.of(journeyId, JourneyStatus.ACTIVE, JourneyKind.NAVIGATION, 1L, 0L, 1, 0,
                Instant.parse("2026-07-31T09:03:00Z"), null,
                Instant.parse("2026-07-31T09:00:00Z"),
                List.of(new JourneyLegView(10L, 0, JourneyLegType.WAIT_BUS, JourneyLegState.ACTIVE,
                        "ST-A", "ST-A", "정류장A", "정류장A", null,
                        null, null, 100L,
                        List.of(new JourneyRouteOptionView(100L, 0, 0, null, "BL-370", "370",
                                null, null, null, null)),
                        "05229", "05229", null, null, null,
                        List.of(new JourneyLegView.FastAlight("2-3", "STAIRS")), "RIGHT")));
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
                .andExpect(jsonPath("$.legs[0].routeOptions[0].routeName").value("370"))
                // #266 안내 값 — 이름이 앱 계약. 모르면 키는 오고 값이 null
                .andExpect(jsonPath("$.legs[0].startStopNo").value("05229"))
                .andExpect(jsonPath("$.legs[0].endStopNo").value("05229"))
                .andExpect(jsonPath("$.legs[0].directionName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.legs[0].startExitNo").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.legs[0].endExitNo").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.legs[0].fastAlight[0].carDoor").value("2-3"))
                .andExpect(jsonPath("$.legs[0].fastAlight[0].facility").value("STAIRS"))
                // #335 내리는 문
                .andExpect(jsonPath("$.legs[0].alightDoorSide").value("RIGHT"));
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
                .andExpect(jsonPath("$.details.journeyId").value(42));
    }

    @Test
    @DisplayName("스냅샷이 만료됐으면 410")
    void start_expired_returns410() throws Exception {
        willThrow(new BusinessException(JourneyErrorCode.SNAPSHOT_EXPIRED))
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
        willThrow(new BusinessException(JourneyErrorCode.SNAPSHOT_OWNER_MISMATCH))
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
        willThrow(new BusinessException(JourneyErrorCode.JOURNEY_COMPILE_FAILED))
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


    // ── 명령 ─────────────────────────────────────────────────────────────────

    private static final String EVENT_BODY = """
            {"eventId":"11111111-2222-3333-4444-555555555555",
             "type":"CLIENT_LEG_TRANSITION_DETECTED",
             "baseCommandVersion":1,
             "routeRevision":1,
             "fromLegIndex":0,
             "toLegIndex":1}
            """;

    private static final String END_BODY = """
            {"eventId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
             "baseCommandVersion":1,
             "routeRevision":1}
            """;

    private void givenOutcome(JourneyEventResult result, String reason) {
        given(applyJourneyEventUseCase.apply(eq(7L), eq(5L), any(JourneyCommand.class)))
                .willReturn(new JourneyEventOutcome(result, reason, snapshot(5L)));
    }

    @Test
    @DisplayName("명령이 반영되면 200 과 최신 상태를 준다")
    void applyEvent_returns200() throws Exception {
        givenOutcome(JourneyEventResult.APPLIED, null);

        mockMvc.perform(post("/api/journeys/5/events")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED"))
                .andExpect(jsonPath("$.journey.journeyId").value(5))
                .andExpect(jsonPath("$.reason").doesNotExist());
    }

    @Test
    @DisplayName("재전송도 성공과 같은 200 이다")
    void applyEvent_duplicate_returns200() throws Exception {
        givenOutcome(JourneyEventResult.DUPLICATE, null);

        mockMvc.perform(post("/api/journeys/5/events")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DUPLICATE"))
                .andExpect(jsonPath("$.journey.journeyId").value(5));
    }

    @Test
    @DisplayName("낡은 상태로 보낸 명령은 409 와 최신 상태를 준다")
    void applyEvent_conflict_returns409() throws Exception {
        givenOutcome(JourneyEventResult.CONFLICT, "STALE_COMMAND_VERSION");

        mockMvc.perform(post("/api/journeys/5/events")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("STALE_COMMAND_VERSION"))
                .andExpect(jsonPath("$.journey.commandVersion").value(1));
    }

    @Test
    @DisplayName("규칙상 할 수 없는 명령은 422")
    void applyEvent_rejected_returns422() throws Exception {
        givenOutcome(JourneyEventResult.REJECTED, "INVALID_LEG_TRANSITION_PAYLOAD");

        mockMvc.perform(post("/api/journeys/5/events")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("INVALID_LEG_TRANSITION_PAYLOAD"));
    }

    @Test
    @DisplayName("없는 여정과 남의 여정은 똑같이 404")
    void applyEvent_notFound_returns404() throws Exception {
        willThrow(new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND))
                .given(applyJourneyEventUseCase).apply(eq(7L), eq(5L), any(JourneyCommand.class));

        mockMvc.perform(post("/api/journeys/5/events")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOURNEY_NOT_FOUND"));
    }

    @Test
    @DisplayName("일반 NoSuchElementException은 여정 없음으로 번역하지 않는다")
    void genericNoSuchElement_isNotJourneyNotFound() throws Exception {
        willThrow(new NoSuchElementException())
                .given(startJourneyUseCase).start(eq(7L), any(StartJourneyCommand.class));

        mockMvc.perform(post("/api/journeys")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_SERVER_ERROR"));
    }

    @Test
    @DisplayName("인증 주체 사용자가 없으면 여정 없음이 아닌 공통 리소스 오류를 준다")
    void userNotFound_usesCommonNotFound() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.NOT_FOUND))
                .given(startJourneyUseCase).start(eq(7L), any(StartJourneyCommand.class));

        mockMvc.perform(post("/api/journeys")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/journeys"));
    }

    @Test
    @DisplayName("명령 식별자가 없으면 400")
    void applyEvent_missingEventId_returns400() throws Exception {
        mockMvc.perform(post("/api/journeys/5/events")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CLIENT_LEG_TRANSITION_DETECTED\",\"baseCommandVersion\":1,\"routeRevision\":1,\"fromLegIndex\":0,\"toLegIndex\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인증 없이는 명령을 보낼 수 없다")
    void applyEvent_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/journeys/5/events")
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT_BODY))
                .andExpect(status().isUnauthorized());
    }


    // ── 상세 조회 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("번호로 조회하면 200 과 파생 값까지 준다")
    void detail_returns200() throws Exception {
        given(getActiveJourneyUseCase.get(7L, 5L)).willReturn(Optional.of(snapshot(5L)));

        mockMvc.perform(get("/api/journeys/5").with(authentication(authUser(7L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journeyId").value(5))
                .andExpect(jsonPath("$.currentLeg.type").value("WAIT_BUS"))
                .andExpect(jsonPath("$.recommendedPollSeconds").value(10));
    }

    @Test
    @DisplayName("바뀐 것이 없으면 204 로 본문을 아낀다")
    void detail_unchanged_returns204() throws Exception {
        given(getActiveJourneyUseCase.get(7L, 5L)).willReturn(Optional.of(snapshot(5L)));

        mockMvc.perform(get("/api/journeys/5?afterCommandVersion=1&afterEvaluationVersion=0")
                        .with(authentication(authUser(7L))))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("명령 번호가 올랐으면 본문을 준다")
    void detail_changed_returns200() throws Exception {
        given(getActiveJourneyUseCase.get(7L, 5L)).willReturn(Optional.of(snapshot(5L)));

        mockMvc.perform(get("/api/journeys/5?afterCommandVersion=0")
                        .with(authentication(authUser(7L))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("링 버전(#307): 같으면 204, 링이 바뀌었으면 두 버전이 그대로여도 200, JSON 에 버전 노출")
    void detail_guidanceVersion() throws Exception {
        given(getActiveJourneyUseCase.get(7L, 5L)).willReturn(Optional.of(
                snapshot(5L).withSubwayGuidance(guidance(1_791_374_000_000L))));

        mockMvc.perform(get("/api/journeys/5?afterCommandVersion=1&afterEvaluationVersion=0"
                                + "&afterGuidanceVersion=1791374000000")
                        .with(authentication(authUser(7L))))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/journeys/5?afterCommandVersion=1&afterEvaluationVersion=0"
                                + "&afterGuidanceVersion=1791373990000")
                        .with(authentication(authUser(7L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guidanceVersion").value(1_791_374_000_000L))
                .andExpect(jsonPath("$.subwayGuidance.version").value(1_791_374_000_000L))
                // 승차역 전 여부(#469)는 서버 안에서만 쓴다
                .andExpect(jsonPath("$.subwayGuidance.trainBeforeBoarding").doesNotExist());
    }

    @Test
    @DisplayName("링 버전을 안 보내는 옛 앱은 두 버전만 본다 — 링이 바뀌어도 204")
    void detail_withoutGuidanceVersion_keepsOldRule() throws Exception {
        given(getActiveJourneyUseCase.get(7L, 5L)).willReturn(Optional.of(
                snapshot(5L).withSubwayGuidance(guidance(1_791_374_000_000L))));

        mockMvc.perform(get("/api/journeys/5?afterCommandVersion=1&afterEvaluationVersion=0")
                        .with(authentication(authUser(7L))))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("링이 없으면 버전 0 — 0 을 보내면 204, 링 없는 스냅샷에도 칸은 있다")
    void detail_noRing_guidanceVersionZero() throws Exception {
        given(getActiveJourneyUseCase.get(7L, 5L)).willReturn(Optional.of(snapshot(5L)));

        mockMvc.perform(get("/api/journeys/5?afterGuidanceVersion=0")
                        .with(authentication(authUser(7L))))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/journeys/5").with(authentication(authUser(7L))))
                .andExpect(jsonPath("$.guidanceVersion").value(0));
    }

    @Test
    @DisplayName("버전 사전 확인(#415-6): 바뀐 것 없으면 여정 전체를 조립하지 않고 204")
    void detail_precheckUnchanged_skipsFullLoad() throws Exception {
        given(getActiveJourneyUseCase.versions(7L, 5L)).willReturn(Optional.of(
                new JourneyQueryUseCase.JourneyVersions(1, 0, false)));

        mockMvc.perform(get("/api/journeys/5?afterCommandVersion=1&afterEvaluationVersion=0")
                        .with(authentication(authUser(7L))))
                .andExpect(status().isNoContent());

        org.mockito.BDDMockito.then(getActiveJourneyUseCase).should(org.mockito.Mockito.never()).get(7L, 5L);
    }

    @Test
    @DisplayName("버전 사전 확인: 명령 번호가 올랐으면 전체를 조립해 200")
    void detail_precheckChanged_loadsFull() throws Exception {
        given(getActiveJourneyUseCase.versions(7L, 5L)).willReturn(Optional.of(
                new JourneyQueryUseCase.JourneyVersions(1, 0, false)));
        given(getActiveJourneyUseCase.get(7L, 5L)).willReturn(Optional.of(snapshot(5L)));

        mockMvc.perform(get("/api/journeys/5?afterCommandVersion=0")
                        .with(authentication(authUser(7L))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("버전 사전 확인: 지하철 단계에서 링 번호를 물으면 두 번호로 가를 수 없어 전체 경로가 링까지 본다")
    void detail_precheckSubwayGuidance_fallsBackToFullPath() throws Exception {
        given(getActiveJourneyUseCase.versions(7L, 5L)).willReturn(Optional.of(
                new JourneyQueryUseCase.JourneyVersions(1, 0, true)));
        given(getActiveJourneyUseCase.get(7L, 5L)).willReturn(Optional.of(
                snapshot(5L).withSubwayGuidance(guidance(1_791_374_000_000L))));

        mockMvc.perform(get("/api/journeys/5?afterCommandVersion=1&afterEvaluationVersion=0"
                                + "&afterGuidanceVersion=1791373990000")
                        .with(authentication(authUser(7L))))
                .andExpect(status().isOk());
    }

    private SubwayGuidance guidance(long version) {
        return new SubwayGuidance(SubwayBindingStatus.CONFIRMED, SubwayRingPhase.RIDE_SUBWAY, "서울2호선",
                "낙성대", "신림(2호선)", 4, java.math.BigDecimal.ZERO, Instant.parse("2026-10-07T11:52:24Z"),
                new java.math.BigDecimal("0.90"), Instant.parse("2026-10-07T11:54:24Z"),
                Instant.parse("2026-10-07T11:54:24Z"), SubwayRingQuality.NORMAL,
                Instant.parse("2026-10-07T11:52:24Z"), version);
    }

    @Test
    @DisplayName("없는 여정은 404")
    void detail_notFound_returns404() throws Exception {
        given(getActiveJourneyUseCase.get(7L, 5L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/journeys/5").with(authentication(authUser(7L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOURNEY_NOT_FOUND"));
    }

    @Test
    @DisplayName("인증 없이는 조회할 수 없다")
    void detail_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/journeys/5"))
                .andExpect(status().isUnauthorized());
    }

    // ── 종료 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("도착 command도 eventId와 version을 보낸다")
    void complete_withVersionedBody() throws Exception {
        given(applyJourneyEventUseCase.apply(eq(7L), eq(5L), any(JourneyCommand.class)))
                .willReturn(new JourneyEventOutcome(
                        JourneyEventResult.APPLIED, null, snapshot(5L)));

        mockMvc.perform(post("/api/journeys/5/complete")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(END_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED"));

        verify(applyJourneyEventUseCase).apply(eq(7L), eq(5L),
                org.mockito.ArgumentMatchers.argThat(c ->
                        c.type() == JourneyEventType.JOURNEY_COMPLETED
                                && c.baseCommandVersion() == 1L
                                && c.routeRevision() == 1
                                && c.eventId() != null));
    }

    @Test
    @DisplayName("취소는 DELETE 로 보낸다")
    void cancel_usesDelete() throws Exception {
        given(applyJourneyEventUseCase.apply(eq(7L), eq(5L), any(JourneyCommand.class)))
                .willReturn(new JourneyEventOutcome(
                        JourneyEventResult.APPLIED, null, snapshot(5L)));

        mockMvc.perform(delete("/api/journeys/5")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(END_BODY))
                .andExpect(status().isOk());

        verify(applyJourneyEventUseCase).apply(eq(7L), eq(5L),
                org.mockito.ArgumentMatchers.argThat(c ->
                        c.type() == JourneyEventType.JOURNEY_CANCELED));
    }

    @Test
    @DisplayName("종료에 명령 식별자를 실으면 재전송을 흡수할 수 있다")
    void cancel_withEventId() throws Exception {
        UUID eventId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        given(applyJourneyEventUseCase.apply(eq(7L), eq(5L), any(JourneyCommand.class)))
                .willReturn(new JourneyEventOutcome(
                        JourneyEventResult.DUPLICATE, null, snapshot(5L)));

        mockMvc.perform(delete("/api/journeys/5")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + eventId
                                + "\",\"baseCommandVersion\":1,\"routeRevision\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DUPLICATE"));

        verify(applyJourneyEventUseCase).apply(eq(7L), eq(5L),
                org.mockito.ArgumentMatchers.argThat(c -> eventId.equals(c.eventId())));
    }

    @Test
    @DisplayName("이미 끝난 여정을 또 끝내려 하면 409")
    void complete_alreadyEnded_returns409() throws Exception {
        given(applyJourneyEventUseCase.apply(eq(7L), eq(5L), any(JourneyCommand.class)))
                .willReturn(new JourneyEventOutcome(
                        JourneyEventResult.CONFLICT, "JOURNEY_NOT_ACTIVE", snapshot(5L)));

        mockMvc.perform(post("/api/journeys/5/complete")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(END_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("JOURNEY_NOT_ACTIVE"));
    }

    @Test
    @DisplayName("없는 여정을 완료하려 하면 404 공통 오류를 준다")
    void complete_notFound_returns404() throws Exception {
        willThrow(new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND))
                .given(applyJourneyEventUseCase).apply(eq(7L), eq(5L), any(JourneyCommand.class));

        mockMvc.perform(post("/api/journeys/5/complete")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(END_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOURNEY_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/journeys/5/complete"));
    }

    @Test
    @DisplayName("없는 여정을 취소하려 하면 404 공통 오류를 준다")
    void cancel_notFound_returns404() throws Exception {
        willThrow(new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND))
                .given(applyJourneyEventUseCase).apply(eq(7L), eq(5L), any(JourneyCommand.class));

        mockMvc.perform(delete("/api/journeys/5")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(END_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOURNEY_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/journeys/5"));
    }

    @Test
    @DisplayName("events API는 resume·종료·lease 이벤트를 받지 않는다")
    void applyEvent_onlyAllowsClientLegTransition() throws Exception {
        String body = EVENT_BODY.replace("CLIENT_LEG_TRANSITION_DETECTED", "JOURNEY_CANCELED");

        mockMvc.perform(post("/api/journeys/5/events")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    // ── 예측(#470) ───────────────────────────────────────────────────────────
    // 앱은 스냅샷이 실린 응답마다 스냅샷을 통째로 갈아끼운다. 조회만 예측을 싣던 때에는 다른 응답으로
    // 갈아끼우는 순간 도착 예정이 사라지고, 조건부 조회가 204 라 다음 번호까지 돌아오지 않았다.

    private static final Instant JOURNEY_ETA = Instant.parse("2026-07-31T09:22:00Z");

    /** 합성기가 받은 스냅샷에 목적지 예측을 얹어 돌려준다. 사용자 번호가 그대로 넘어가는지도 함께 본다. */
    private void givenEtaComposed() {
        given(etaComposer.compose(eq(7L), any())).willAnswer(call -> {
            JourneySnapshot snapshot = call.getArgument(1);
            return snapshot.withEta(new JourneyEtaView(null, null, null, JOURNEY_ETA, null, null),
                    snapshot.recommendedPollSeconds());
        });
    }

    @Test
    @DisplayName("#470 시작 응답도 조회와 같은 예측을 싣는다")
    void start_carriesEta() throws Exception {
        givenEtaComposed();
        given(startJourneyUseCase.start(eq(7L), any(StartJourneyCommand.class)))
                .willReturn(snapshot(1L));

        mockMvc.perform(post("/api/journeys")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eta.journeyEta").value("2026-07-31T09:22:00Z"));
    }

    @Test
    @DisplayName("#470 조회 응답은 그대로 예측을 싣는다 — 쓰기 응답과 같은 합성이다")
    void active_carriesEta() throws Exception {
        givenEtaComposed();
        given(getActiveJourneyUseCase.getActive(7L)).willReturn(Optional.of(snapshot(5L)));

        mockMvc.perform(get("/api/journeys/active").with(authentication(authUser(7L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eta.journeyEta").value("2026-07-31T09:22:00Z"));
    }

    @Test
    @DisplayName("#470 이벤트 응답의 스냅샷도 예측을 싣는다")
    void applyEvent_carriesEta() throws Exception {
        givenEtaComposed();
        givenOutcome(JourneyEventResult.APPLIED, null);

        mockMvc.perform(post("/api/journeys/5/events")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED"))
                .andExpect(jsonPath("$.journey.eta.journeyEta").value("2026-07-31T09:22:00Z"));
    }

    @Test
    @DisplayName("#470 거절·충돌 응답의 스냅샷도 예측을 싣는다 — 앱은 그것으로 화면을 다시 그린다")
    void rejectedEvent_carriesEta() throws Exception {
        givenEtaComposed();
        givenOutcome(JourneyEventResult.CONFLICT, null);

        mockMvc.perform(post("/api/journeys/5/events")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.journey.eta.journeyEta").value("2026-07-31T09:22:00Z"));
    }

    @Test
    @DisplayName("#470 도착·취소 응답의 스냅샷도 같은 합성을 탄다")
    void end_carriesEta() throws Exception {
        givenEtaComposed();
        givenOutcome(JourneyEventResult.APPLIED, null);

        mockMvc.perform(post("/api/journeys/5/complete")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(END_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journey.eta.journeyEta").value("2026-07-31T09:22:00Z"));
        mockMvc.perform(delete("/api/journeys/5")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON).content(END_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journey.eta.journeyEta").value("2026-07-31T09:22:00Z"));
    }

    // ── 보호 ─────────────────────────────────────────────────────────────────



    @Test
    @DisplayName("인증 없이는 시작할 수 없다")
    void start_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SECURITY_UNAUTHENTICATED"))
                .andExpect(jsonPath("$.path").value("/api/journeys"));
    }

    @Test
    @DisplayName("인증 없이는 조회할 수 없다")
    void active_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/journeys/active"))
                .andExpect(status().isUnauthorized());
    }
}
