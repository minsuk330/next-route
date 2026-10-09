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
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartTrackCommand;
import watoo.grd.nextroute.application.journey.dto.TrackViews;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.journey.service.TrackJourneyStartService;
import watoo.grd.nextroute.application.journey.service.TrackSelectionQueryService;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;
import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.GlobalExceptionHandler;
import watoo.grd.nextroute.global.error.code.CommonErrorCode;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 단독 추적 API 의 경로·인증·상태 코드 계약(#265). {@code SecurityConfig} 는 "적어 둔 경로만 보호"라
 * {@code /api/journeys/**} 아래에 있어야 익명에 안 열린다.
 */
@WebMvcTest(JourneyTrackController.class)
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class, JourneyControllerAdvice.class})
class JourneyTrackControllerTest {

    private static final String BODY = """
            {"routeGtfsId":"1:RR_ACC1_S-1-02-1I","boardStopId":"1:RS_ACC1_S-1-0222",
             "alightStopId":"1:RS_ACC1_S-1-0226","patternCode":"1:RR_ACC1_S-1-02-1I::10"}""";

    @Autowired MockMvc mockMvc;
    @MockBean TrackSelectionQueryService queryService;
    @MockBean TrackJourneyStartService startService;
    @MockBean JwtProvider jwtProvider;
    @MockBean TokenGenerationPort tokenGenerationPort;
    @MockBean AdminProperties adminProperties;

    @Test
    @DisplayName("401 — 조회·시작 전부 인증 필요")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/journeys/track/stops/near?lat=37.5&lon=127.0")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/journeys/track/stops/1:BS_TAGO_SEB121000091/patterns"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/journeys/track/routes/1:BR_TAGO_SEB100100093/shape?from=1:BS_X"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/journeys/track/routes/1:BR_TAGO_SEB100100093/stops?from=1:BS_X"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/journeys/track").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("200 — 방향 목록. 콜론 든 GTFS id 를 경로 변수로 받는다")
    void directions() throws Exception {
        given(queryService.directions("1:RS_ACC1_S-1-0222")).willReturn(List.of(new TrackViews.Direction(
                "1:RR_ACC1_S-1-02-1I", RoutingMode.SUBWAY, "서울2호선", "#00A23F", "내선", "교대(2호선)",
                "1:RR_ACC1_S-1-02-1I::10", null, "1:RS_ACC1_S-1-0222", null)));

        mockMvc.perform(get("/api/journeys/track/stops/1:RS_ACC1_S-1-0222/patterns").with(authentication(user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].directionLabel").value("내선"))
                .andExpect(jsonPath("$[0].mode").value("SUBWAY"))
                .andExpect(jsonPath("$[0].patternCode").value("1:RR_ACC1_S-1-02-1I::10"))
                .andExpect(jsonPath("$[0].boardStopGtfsId").value("1:RS_ACC1_S-1-0222"))
                .andExpect(jsonPath("$[0].arrivalStopId").doesNotExist());
    }

    @Test
    @DisplayName("200 — 노선 선: points 는 x=경도·y=위도, quality 문자열. to·pattern 은 선택")
    void shape() throws Exception {
        given(queryService.shape("1:BR_TAGO_SEB100100093", "1:BS_A", null, null)).willReturn(new TrackViews.Shape(
                List.of(new CoordPoint(126.83, 37.52), new CoordPoint(126.84, 37.53)),
                TrackViews.ShapeQuality.SHAPE_OK));

        mockMvc.perform(get("/api/journeys/track/routes/1:BR_TAGO_SEB100100093/shape?from=1:BS_A")
                        .with(authentication(user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quality").value("SHAPE_OK"))
                .andExpect(jsonPath("$.points[0].x").value(126.83))
                .andExpect(jsonPath("$.points[0].y").value(37.52));

        mockMvc.perform(get("/api/journeys/track/routes/1:BR_TAGO_SEB100100093/shape?from=1:BS_A&to=1:BS_B&pattern=p")
                        .with(authentication(user())))
                .andExpect(status().isOk());
        verify(queryService).shape("1:BR_TAGO_SEB100100093", "1:BS_A", "1:BS_B", "p");
    }

    @Test
    @DisplayName("200 — 하차 후보, mode 필터")
    void alightAndNear() throws Exception {
        given(queryService.alightStops("1:RR_ACC1_S-1-02-1I", "1:RS_ACC1_S-1-0222", null)).willReturn(List.of(
                new TrackViews.AlightStop("1:RS_ACC1_S-1-0223", "역삼", 37.5, 127.03, 0)));

        mockMvc.perform(get("/api/journeys/track/routes/1:RR_ACC1_S-1-02-1I/stops?from=1:RS_ACC1_S-1-0222")
                        .with(authentication(user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].index").value(0));

        given(queryService.stopsNear(37.5, 127.0, RoutingMode.SUBWAY)).willReturn(List.of(new TrackViews.Stop(
                "1:RS_ACC1_S-1-0222", "강남(2호선)", 37.4981, 127.0281, RoutingMode.SUBWAY, 64,
                null, null, "1002000222", "1002", "2호선")));
        mockMvc.perform(get("/api/journeys/track/stops/near?lat=37.5&lon=127.0&mode=SUBWAY")
                        .with(authentication(user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].arrivalStationId").value("1002000222"))
                .andExpect(jsonPath("$[0].lineId").value("1002"))
                .andExpect(jsonPath("$[0].distanceMeters").value(64));
        verify(queryService).stopsNear(37.5, 127.0, RoutingMode.SUBWAY);
    }

    @Test
    @DisplayName("201 — 시작. replace 는 쿼리 파라미터")
    void start() throws Exception {
        given(startService.start(eq(1L), any())).willReturn(JourneySnapshot.of(9L, JourneyStatus.ACTIVE,
                JourneyKind.TRACK, 1L, 0L, 1, 0, Instant.parse("2026-10-07T03:03:00Z"), null,
                Instant.parse("2026-10-07T03:00:00Z"), List.of()));

        mockMvc.perform(post("/api/journeys/track?replace=true").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY).with(authentication(user())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("TRACK"));
        verify(startService).start(1L, new StartTrackCommand("1:RR_ACC1_S-1-02-1I", "1:RS_ACC1_S-1-0222",
                "1:RS_ACC1_S-1-0226", "1:RR_ACC1_S-1-02-1I::10", true));
    }

    @Test
    @DisplayName("오류 코드: 플래그 off 404 / 승하차 422 / 원천 503 / 본문 누락 400")
    void errors() throws Exception {
        given(startService.start(eq(1L), any()))
                .willThrow(new BusinessException(CommonErrorCode.NOT_FOUND))
                .willThrow(new BusinessException(JourneyErrorCode.TRACK_STOPS_INVALID))
                .willThrow(new BusinessException(JourneyErrorCode.TRACK_PATTERN_UNAVAILABLE));

        mockMvc.perform(post("/api/journeys/track").contentType(MediaType.APPLICATION_JSON).content(BODY)
                .with(authentication(user()))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/journeys/track").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(user())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRACK_STOPS_INVALID"));
        mockMvc.perform(post("/api/journeys/track").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(user())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TRACK_PATTERN_UNAVAILABLE"));
        mockMvc.perform(post("/api/journeys/track").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeGtfsId\":\"r\"}").with(authentication(user())))
                .andExpect(status().isBadRequest());
    }

    private Authentication user() {
        return new UsernamePasswordAuthenticationToken(1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
