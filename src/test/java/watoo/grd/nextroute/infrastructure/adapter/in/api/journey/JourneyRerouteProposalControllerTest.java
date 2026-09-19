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
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateDisplay;
import watoo.grd.nextroute.application.journey.dto.RerouteProposalView;
import watoo.grd.nextroute.application.journey.port.in.AcceptRerouteProposalUseCase;
import watoo.grd.nextroute.application.journey.service.RerouteProposalService;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;
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
        return new RerouteProposalView(1L, JOURNEY_ID, 1, 0, null, RerouteProposalKind.REMAINING_PATH,
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
