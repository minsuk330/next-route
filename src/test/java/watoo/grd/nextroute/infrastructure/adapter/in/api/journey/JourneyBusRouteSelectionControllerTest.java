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
import org.springframework.test.web.servlet.ResultActions;
import watoo.grd.nextroute.application.auth.config.AdminProperties;
import watoo.grd.nextroute.application.auth.port.out.TokenGenerationPort;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.exception.StaleRouteRevisionException;
import watoo.grd.nextroute.application.journey.service.BusRouteSelectionService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 탄 버스 노선 선택 API(#598) 경로·인증·본문·상태 코드. */
@WebMvcTest(JourneyBusRouteSelectionController.class)
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class, JourneyControllerAdvice.class})
class JourneyBusRouteSelectionControllerTest {

    private static final String PATH = "/api/journeys/7/bus/route-selection";
    private static final String BODY = "{\"routeOptionId\":4812,\"routeRevision\":3}";

    @Autowired MockMvc mockMvc;
    @MockBean BusRouteSelectionService selectionService;
    @MockBean JourneySnapshotEtaAttacher etaAttacher;
    @MockBean JwtProvider jwtProvider;
    @MockBean TokenGenerationPort tokenGenerationPort;
    @MockBean AdminProperties adminProperties;

    @Test
    @DisplayName("401 — 인증 필요")
    void requiresAuth() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("200 — 본문의 후보·개정본·대상 구간이 전달되고 여정 스냅샷이 돌아온다(legIndex 는 생략 가능)")
    void selects() throws Exception {
        JourneySnapshot snapshot = snapshot();
        given(selectionService.select(1L, 7L, 4812L, 3, 4)).willReturn(snapshot);
        given(selectionService.select(1L, 7L, 4812L, 3, null)).willReturn(snapshot);
        given(etaAttacher.forWrite(1L, snapshot)).willReturn(snapshot);

        perform("{\"routeOptionId\":4812,\"routeRevision\":3,\"legIndex\":4}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journeyId").value(7))
                .andExpect(jsonPath("$.routeRevision").value(3))
                .andExpect(jsonPath("$.commandVersion").value(5));
        perform(BODY).andExpect(status().isOk()).andExpect(jsonPath("$.journeyId").value(7));
    }

    @Test
    @DisplayName("오류 코드: 404 남의 여정 / 409 끝난 여정 / 409 개정본 / 422 고를 수 없는 구간 / 422 후보 아님 / 400 본문")
    void errors() throws Exception {
        given(selectionService.select(eq(1L), eq(7L), anyLong(), anyInt(), any()))
                .willThrow(new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND))
                .willThrow(new BusinessException(JourneyErrorCode.JOURNEY_NOT_ACTIVE))
                .willThrow(new StaleRouteRevisionException(4))
                .willThrow(new BusinessException(JourneyErrorCode.BUS_ROUTE_SELECTION_NOT_ALLOWED))
                .willThrow(new BusinessException(JourneyErrorCode.BUS_ROUTE_OPTION_NOT_FOUND));

        perform(BODY).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("JOURNEY_NOT_FOUND"));
        perform(BODY).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOURNEY_NOT_ACTIVE"));
        perform(BODY).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_ROUTE_REVISION"));
        perform(BODY).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUS_ROUTE_SELECTION_NOT_ALLOWED"));
        perform(BODY).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUS_ROUTE_OPTION_NOT_FOUND"));
        perform("{\"routeRevision\":3}").andExpect(status().isBadRequest());
        perform("{\"routeOptionId\":4812,\"routeRevision\":0}").andExpect(status().isBadRequest());
    }

    private ResultActions perform(String body) throws Exception {
        return mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(authentication(user())));
    }

    private static JourneySnapshot snapshot() {
        return new JourneySnapshot(7L, JourneyStatus.ACTIVE, null, 5L, 9L, 3, 0, null, null,
                Instant.parse("2026-11-16T09:00:00Z"), null, 15, List.of(), null, null);
    }

    private Authentication user() {
        return new UsernamePasswordAuthenticationToken(1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
