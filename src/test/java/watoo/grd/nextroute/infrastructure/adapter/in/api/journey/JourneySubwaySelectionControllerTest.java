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
import watoo.grd.nextroute.application.journey.dto.SubwayBindingView;
import watoo.grd.nextroute.application.journey.dto.SubwayTrackabilityView;
import watoo.grd.nextroute.application.journey.dto.SubwayTrainCandidate;
import watoo.grd.nextroute.application.journey.exception.StaleRouteRevisionException;
import watoo.grd.nextroute.application.journey.service.SubwayTrainSelectionService;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.GlobalExceptionHandler;
import watoo.grd.nextroute.global.error.code.CommonErrorCode;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 열차 후보·선택 API(#265) 경로·인증·상태 코드. */
@WebMvcTest(JourneySubwayBindingController.class)
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class, JourneyControllerAdvice.class})
class JourneySubwaySelectionControllerTest {

    private static final String BODY = "{\"trainNo\":\"2390\",\"routeRevision\":1}";

    @Autowired MockMvc mockMvc;
    @MockBean SubwayTrainSelectionService selectionService;
    @MockBean JwtProvider jwtProvider;
    @MockBean TokenGenerationPort tokenGenerationPort;
    @MockBean AdminProperties adminProperties;

    @Test
    @DisplayName("401 — 인증 필요")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/journeys/7/subway/train-candidates")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/journeys/7/subway/train-selection").contentType(MediaType.APPLICATION_JSON)
                .content(BODY)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("200 — 후보에 열차번호·행선지·급행")
    void candidates() throws Exception {
        given(selectionService.candidates(1L, 7L, null)).willReturn(List.of(
                new SubwayTrainCandidate("2390", "성수", false, "일반", 90, Instant.parse("2026-10-07T03:00:00Z"),
                        SubwayTrainCandidate.Kind.UPCOMING),
                new SubwayTrainCandidate("2388", null, false, null, null, Instant.parse("2026-10-07T02:58:00Z"),
                        SubwayTrainCandidate.Kind.DEPARTED)));

        mockMvc.perform(get("/api/journeys/7/subway/train-candidates").with(authentication(user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].trainNo").value("2390"))
                .andExpect(jsonPath("$[0].destination").value("성수"))
                .andExpect(jsonPath("$[0].express").value(false))
                .andExpect(jsonPath("$[0].kind").value("UPCOMING"))
                .andExpect(jsonPath("$[1].kind").value("DEPARTED"))
                .andExpect(jsonPath("$[1].arrivalSeconds").doesNotExist());
    }

    @Test
    @DisplayName("200 — legIndex 쿼리가 대상 탑승 구간으로 전달된다(지하 환승)")
    void candidatesForLeg() throws Exception {
        given(selectionService.candidates(1L, 7L, 5)).willReturn(List.of());

        mockMvc.perform(get("/api/journeys/7/subway/train-candidates").param("legIndex", "5")
                        .with(authentication(user())))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(selectionService).candidates(1L, 7L, 5);
    }

    @Test
    @DisplayName("200 — 추적 가능 여부와 이유")
    void trackability() throws Exception {
        given(selectionService.trackability(1L, 7L, 3)).willReturn(
                SubwayTrackabilityView.no(3, SubwayTrackabilityView.Reason.NO_REALTIME_LINE));

        mockMvc.perform(get("/api/journeys/7/subway/trackability").param("legIndex", "3")
                        .with(authentication(user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legIndex").value(3))
                .andExpect(jsonPath("$.trackable").value(false))
                .andExpect(jsonPath("$.reason").value("NO_REALTIME_LINE"));
    }

    @Test
    @DisplayName("200 — 선택 결과 상태")
    void select() throws Exception {
        given(selectionService.select(1L, 7L, "2390", 1, null))
                .willReturn(new SubwayBindingView(SubwayBindingStatus.CONFIRMED, 1, 1));

        mockMvc.perform(post("/api/journeys/7/subway/train-selection").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY).with(authentication(user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("오류 코드: 404 꺼짐 / 409 대기 아님 / 422 후보 밖 / 409 개정본 / 400 본문")
    void errors() throws Exception {
        given(selectionService.select(eq(1L), eq(7L), anyString(), anyInt(), org.mockito.ArgumentMatchers.isNull()))
                .willThrow(new BusinessException(CommonErrorCode.NOT_FOUND))
                .willThrow(new BusinessException(JourneyErrorCode.NOT_WAITING_FOR_SUBWAY))
                .willThrow(new BusinessException(JourneyErrorCode.TRAIN_NOT_IN_CANDIDATES))
                .willThrow(new StaleRouteRevisionException(2));

        perform().andExpect(status().isNotFound());
        perform().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_WAITING_FOR_SUBWAY"));
        perform().andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRAIN_NOT_IN_CANDIDATES"));
        perform().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_ROUTE_REVISION"));
        mockMvc.perform(post("/api/journeys/7/subway/train-selection").contentType(MediaType.APPLICATION_JSON)
                .content("{\"routeRevision\":1}").with(authentication(user()))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("200 — 본문 legIndex 가 대상 탑승 구간으로 전달된다")
    void selectForLeg() throws Exception {
        given(selectionService.select(1L, 7L, "2390", 1, 5))
                .willReturn(new SubwayBindingView(SubwayBindingStatus.CONFIRMED, 5, 1));

        mockMvc.perform(post("/api/journeys/7/subway/train-selection").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trainNo\":\"2390\",\"routeRevision\":1,\"legIndex\":5}")
                        .with(authentication(user())))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions perform() throws Exception {
        return mockMvc.perform(post("/api/journeys/7/subway/train-selection").contentType(MediaType.APPLICATION_JSON)
                .content(BODY).with(authentication(user())));
    }

    private Authentication user() {
        return new UsernamePasswordAuthenticationToken(1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
