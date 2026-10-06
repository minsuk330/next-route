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
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteDecision;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateDisplay;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateRoute;
import watoo.grd.nextroute.application.journey.dto.RerouteProposalView;
import watoo.grd.nextroute.application.journey.port.in.AcceptRerouteProposalUseCase;
import watoo.grd.nextroute.application.journey.port.in.GetRerouteCandidateRouteUseCase;
import watoo.grd.nextroute.application.journey.service.RerouteProposalService;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalStatus;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
 * 재탐색 API 의 상태 코드 계약과 <b>응답에 무엇이 실리지 않는가</b>.
 *
 * <p>두 번째가 더 중요하다. 적용 재료는 후보 하나에 경로 원본이 통째로 들어가 후보 3개면 수십
 * KB 다. 그것이 재탐색 화면을 열 때마다 이동통신망으로 나가면 지하철에서 초조한 사용자에게 가장
 * 나쁜 크기이고, 앱은 그 값을 쓰지도 않는다.
 */
@WebMvcTest(JourneyRerouteProposalController.class)
@Import({SecurityConfig.class, CorsConfig.class})
@DisplayName("재탐색 제안 API")
class JourneyRerouteProposalControllerTest {

    private static final Long USER_ID = 7L;
    private static final Long JOURNEY_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    @Autowired MockMvc mockMvc;
    @MockBean RerouteProposalService proposalService;
    @MockBean AcceptRerouteProposalUseCase acceptUseCase;
    @MockBean GetRerouteCandidateRouteUseCase candidateRouteUseCase;
    @MockBean JwtProvider jwtProvider;                 // SecurityConfig 의존
    @MockBean TokenGenerationPort tokenGenerationPort; // SecurityConfig(JwtAuthFilter) 의존
    @MockBean AdminProperties adminProperties;         // SecurityConfig(AdminApiKeyFilter) 의존

    // ── 기본 상태 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("제안이 없으면 200 과 proposal: null 이다")
    void emptyProposalIsNotAnError() throws Exception {
        given(proposalService.findActive(USER_ID, JOURNEY_ID)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/journeys/{id}/reroute-proposals/active", JOURNEY_ID)
                        .with(authentication(authUser(USER_ID))))
                .andExpect(status().isOk())
                // 204 로 가르면 '없음'이 앱의 오류 처리 경로로 새기 쉽다.
                .andExpect(jsonPath("$.proposal").doesNotExist());
    }

    @Test
    @DisplayName("기본 상태에서 요청은 409 REROUTE_NOT_ELIGIBLE 이다")
    void requestIsRejectedByDefault() throws Exception {
        willThrow(new BusinessException(JourneyErrorCode.REROUTE_NOT_ELIGIBLE))
                .given(proposalService).request(anyLong(), anyLong(), anyInt());

        mockMvc.perform(post("/api/journeys/{id}/reroute-proposals", JOURNEY_ID)
                        .with(authentication(authUser(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transferIndex":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REROUTE_NOT_ELIGIBLE"));
    }

    @Test
    @DisplayName("인증 없이는 401 이다")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/journeys/{id}/reroute-proposals/active", JOURNEY_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("본문의 userId 는 무시한다 — 사용자는 토큰에서만 온다")
    void bodyUserIdIsIgnored() throws Exception {
        given(proposalService.request(eq(USER_ID), eq(JOURNEY_ID), anyInt()))
                .willReturn(Optional.empty());

        mockMvc.perform(post("/api/journeys/{id}/reroute-proposals", JOURNEY_ID)
                        .with(authentication(authUser(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transferIndex":1,"userId":999}
                                """))
                .andExpect(status().isOk());

        verify(proposalService).request(USER_ID, JOURNEY_ID, 1);
    }

    // ── 주소 계약 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("제안의 주소는 origin 이다 — 옛 transferIndex 는 응답에 없다")
    void proposalCarriesOriginAsItsAddress() throws Exception {
        given(proposalService.findActive(USER_ID, JOURNEY_ID))
                .willReturn(Optional.of(viewWithRemainingPath()));

        mockMvc.perform(get("/api/journeys/{id}/reroute-proposals/active", JOURNEY_ID)
                        .with(authentication(authUser(USER_ID))))
                .andExpect(status().isOk())
                // 앱이 "어느 자리에 대한 제안인지"를 읽는 값. leg 번호와 정류장이 함께 있어야
                // 주행 중 정류장 하차(RIDE_INTERIOR)까지 가리킬 수 있다.
                .andExpect(jsonPath("$.proposal.origin.legIndex").value(2))
                .andExpect(jsonPath("$.proposal.origin.stopId").value("ST-C"))
                .andExpect(jsonPath("$.proposal.origin.kind").value("TRANSFER_WAIT"))
                // 옛 주소를 되살리면 주소가 둘이 되고, 그때부터 어느 쪽이 진짜인지 코드가 매번
                // 골라야 한다(stage5 가 지운 이유).
                .andExpect(jsonPath("$.proposal.transferIndex").doesNotExist())
                // isRealtimeAddressable() 같은 파생 메서드가 속성으로 새면 계약이 조용히 늘어난다.
                .andExpect(jsonPath("$.proposal.origin.realtimeAddressable").doesNotExist());
    }

    // ── 거절·무시(PR-3 알림 계약) ───────────────────────────────────────────

    @Test
    @DisplayName("거절은 여정의 알림 상태까지 돌려준다 — 앱이 규칙을 다시 계산하지 않게")
    void rejectReturnsJourneyAlertState() throws Exception {
        given(proposalService.decide(USER_ID, JOURNEY_ID, 11L,
                RerouteProposalStatus.REJECTED, false))
                .willReturn(new RerouteDecision(true, true, false));

        mockMvc.perform(post("/api/journeys/{id}/reroute-proposals/{pid}/reject",
                        JOURNEY_ID, 11L)
                        .with(authentication(authUser(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"keepAlerts":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true))
                .andExpect(jsonPath("$.rerouteTerminated").value(true))
                .andExpect(jsonPath("$.rerouteAlertOptIn").value(false));
    }

    @Test
    @DisplayName("본문이 없으면 '계속 받기'는 꺼진 것으로 읽는다")
    void missingBodyMeansNoMoreAlerts() throws Exception {
        given(proposalService.decide(USER_ID, JOURNEY_ID, 11L,
                RerouteProposalStatus.REJECTED, false))
                .willReturn(new RerouteDecision(true, true, false));

        mockMvc.perform(post("/api/journeys/{id}/reroute-proposals/{pid}/reject",
                        JOURNEY_ID, 11L)
                        .with(authentication(authUser(USER_ID))))
                .andExpect(status().isOk());

        verify(proposalService).decide(USER_ID, JOURNEY_ID, 11L,
                RerouteProposalStatus.REJECTED, false);
    }

    @Test
    @DisplayName("무시는 재전송이어도 200 이다 — 멱등")
    void dismissIsIdempotentOverTheWire() throws Exception {
        given(proposalService.decide(USER_ID, JOURNEY_ID, 11L,
                RerouteProposalStatus.DISMISSED, false))
                .willReturn(new RerouteDecision(false, false, false));

        mockMvc.perform(post("/api/journeys/{id}/reroute-proposals/{pid}/dismiss",
                        JOURNEY_ID, 11L)
                        .with(authentication(authUser(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(false));
    }

    // ── 적용 재료가 새어 나가지 않는가 ──────────────────────────────────────

    @Test
    @DisplayName("응답 어디에도 적용 재료가 없다")
    void applyNeverLeaksToResponse() throws Exception {
        given(proposalService.findActive(USER_ID, JOURNEY_ID))
                .willReturn(Optional.of(viewWithRemainingPath()));

        String body = mockMvc.perform(get("/api/journeys/{id}/reroute-proposals/active", JOURNEY_ID)
                        .with(authentication(authUser(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.candidates[0].candidateId").value("c1"))
                .andExpect(jsonPath("$.proposal.candidates[0].display.totalDurationSec").value(1500))
                .andReturn().getResponse().getContentAsString();

        // 문자열 수준으로 본다 - 필드가 하나 늘어도 이 단언이 잡는다.
        assertThat(body)
                .as("후보 3개면 수십 KB 가 이동통신망으로 나간다. 앱은 그 값을 쓰지도 않는다")
                .doesNotContain("apply")
                .doesNotContain("subPaths")
                .doesNotContain("fromLegIndex");
    }

    // ── 후보 경로 조회(#259) ────────────────────────────────────────────────

    private static final String CANDIDATE_ROUTE =
            "/api/journeys/{id}/reroute-proposals/{pid}/candidates/{cid}/route";

    @Test
    @DisplayName("후보 경로는 검색 응답 paths[] 원소 모양의 path 와 지금 타는 구간 ride 로 나간다")
    void candidateRouteCarriesSearchShapedPathAndRide() throws Exception {
        given(candidateRouteUseCase.get(USER_ID, JOURNEY_ID, 11L, "c1"))
                .willReturn(new RerouteCandidateRoute(11L, "c1",
                        new RerouteCandidateRoute.Ride(1, "2412", "뚝섬역", "ST-D", "성수역",
                                127.0559, 37.5446, 3, NOW.plusSeconds(300)),
                        busPath()));

        mockMvc.perform(get(CANDIDATE_ROUTE, JOURNEY_ID, 11L, "c1")
                        .with(authentication(authUser(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposalId").value(11))
                .andExpect(jsonPath("$.candidateId").value("c1"))
                // 앱이 검색 결과 상세 화면을 그대로 태우는 칸들이다.
                .andExpect(jsonPath("$.path.info.firstStartStation").value("정류장A"))
                .andExpect(jsonPath("$.path.subPaths[0].lanes[0].busNo").value("370"))
                .andExpect(jsonPath("$.ride.legIndex").value(1))
                .andExpect(jsonPath("$.ride.routeName").value("2412"))
                .andExpect(jsonPath("$.ride.alightStopId").value("ST-D"))
                .andExpect(jsonPath("$.ride.alightX").value(127.0559))
                .andExpect(jsonPath("$.ride.alightY").value(37.5446))
                .andExpect(jsonPath("$.ride.stopCount").value(3))
                .andExpect(jsonPath("$.ride.alightAt").exists());
    }

    @Test
    @DisplayName("타고 있지 않으면 ride 는 null 이다")
    void candidateRouteWithoutRide() throws Exception {
        given(candidateRouteUseCase.get(USER_ID, JOURNEY_ID, 11L, "c1"))
                .willReturn(new RerouteCandidateRoute(11L, "c1", null, busPath()));

        mockMvc.perform(get(CANDIDATE_ROUTE, JOURNEY_ID, 11L, "c1")
                        .with(authentication(authUser(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ride").doesNotExist())
                .andExpect(jsonPath("$.path.subPaths").isArray());
    }

    @Test
    @DisplayName("없는 후보는 404 REROUTE_CANDIDATE_NOT_FOUND 다")
    void candidateRouteNotFound() throws Exception {
        willThrow(new BusinessException(JourneyErrorCode.REROUTE_CANDIDATE_NOT_FOUND))
                .given(candidateRouteUseCase).get(USER_ID, JOURNEY_ID, 11L, "nope");

        mockMvc.perform(get(CANDIDATE_ROUTE, JOURNEY_ID, 11L, "nope")
                        .with(authentication(authUser(USER_ID))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REROUTE_CANDIDATE_NOT_FOUND"));
    }

    @Test
    @DisplayName("낡은 제안은 409 REROUTE_PROPOSAL_STALE 이다")
    void candidateRouteStale() throws Exception {
        willThrow(new BusinessException(JourneyErrorCode.REROUTE_PROPOSAL_STALE))
                .given(candidateRouteUseCase).get(USER_ID, JOURNEY_ID, 11L, "c1");

        mockMvc.perform(get(CANDIDATE_ROUTE, JOURNEY_ID, 11L, "c1")
                        .with(authentication(authUser(USER_ID))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REROUTE_PROPOSAL_STALE"));
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private Authentication authUser(Long userId) {
        return new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private RerouteProposalView viewWithRemainingPath() {
        RerouteCandidate candidate = new RerouteCandidate("c1",
                RerouteProposalKind.REMAINING_PATH,
                new RerouteCandidateApply.RemainingPathApply(2, busPath()),
                new RerouteCandidateDisplay(NOW.plusSeconds(1_500), 1_500, 240, 1, 300,
                        "REALTIME", "HIGH", NOW.minusSeconds(30), List.of("370 버스")));
        return new RerouteProposalView(1L, JOURNEY_ID, 1,
                new watoo.grd.nextroute.application.journey.dto.RerouteOrigin(2, "ST-C", "BL-370", 4,
                        watoo.grd.nextroute.application.journey.dto.RerouteOriginKind.TRANSFER_WAIT),
                RerouteProposalKind.REMAINING_PATH,
                1L, NOW.minusSeconds(30), NOW.plusSeconds(120), List.of(candidate));
    }

    private PathResult busPath() {
        return new PathResult(1,
                new PathInfo(25, 2000, 500, 1, "정류장A", "정류장F", null),
                List.of(SubPathFixture.bus(20).names("정류장A", "정류장F")
                        .busStops("ST-A", "ST-F")
                        .lanes(SubPathFixture.busLane("370", "BL-370")).build()),
                List.of());
    }
}
