package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import watoo.grd.nextroute.application.subway.dto.TrainKind;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.GlobalExceptionHandler;
import watoo.grd.nextroute.global.error.code.CommonErrorCode;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

    /**
     * 구버전(S3 전) 응답과 같은 입력의 후보 셋 — 일반, 서지 않는 급행(플래그 꺼짐), 떠난 열차, 남은 초 없이 거리만 아는
     * 열차(#489).
     */
    private static List<SubwayTrainCandidate> fourCandidates() {
        return List.of(
                new SubwayTrainCandidate("2390", "성수", false, "일반", 90, Instant.parse("2026-10-07T03:00:00Z"),
                        SubwayTrainCandidate.Kind.UPCOMING, null, "99", TrainKind.NORMAL, "일반", true, true, null,
                        null),
                new SubwayTrainCandidate("9600", "중앙보훈병원", true, "급행", 330,
                        Instant.parse("2026-10-07T03:00:00Z"), SubwayTrainCandidate.Kind.UPCOMING, null, "99",
                        TrainKind.EXPRESS, "급행", false, true, false, "1009-express"),
                new SubwayTrainCandidate("2388", null, false, null, null, Instant.parse("2026-10-07T02:58:00Z"),
                        SubwayTrainCandidate.Kind.DEPARTED, null, null, TrainKind.UNKNOWN, null, true, true, null,
                        null),
                new SubwayTrainCandidate("2394", "성수", false, "일반", null, Instant.parse("2026-10-07T03:00:00Z"),
                        SubwayTrainCandidate.Kind.UPCOMING, 3, "99", TrainKind.NORMAL, "일반", true, true, null,
                        null));
    }

    @Test
    @DisplayName("200 — 후보에 열차번호·행선지·급행·남은 역(#489), 그리고 S3 새 칸(trainKind·trainLabel·trackable·정차·서비스)")
    void candidates() throws Exception {
        given(selectionService.candidates(1L, 7L, null)).willReturn(fourCandidates());

        mockMvc.perform(get("/api/journeys/7/subway/train-candidates").with(authentication(user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].trainNo").value("2390"))
                .andExpect(jsonPath("$[0].destination").value("성수"))
                .andExpect(jsonPath("$[0].express").value(false))
                .andExpect(jsonPath("$[0].kind").value("UPCOMING"))
                .andExpect(jsonPath("$[0].arrivalCode").value("99"))
                .andExpect(jsonPath("$[0].trainKind").value("NORMAL"))
                .andExpect(jsonPath("$[0].trackable").value(true))
                .andExpect(jsonPath("$[1].trainKind").value("EXPRESS"))
                .andExpect(jsonPath("$[1].trainLabel").value("급행"))
                .andExpect(jsonPath("$[1].trackable").value(false))
                .andExpect(jsonPath("$[1].stopsAtBoarding").value(true))
                .andExpect(jsonPath("$[1].stopsAtAlighting").value(false))
                .andExpect(jsonPath("$[1].expressServiceId").value("1009-express"))
                .andExpect(jsonPath("$[2].kind").value("DEPARTED"))
                .andExpect(jsonPath("$[2].arrivalSeconds").doesNotExist())
                .andExpect(jsonPath("$[3].arrivalSeconds").doesNotExist())
                .andExpect(jsonPath("$[3].stationsAway").value(3))
                .andExpect(jsonPath("$[3].arrivalCode").value("99"));
    }

    @Test
    @DisplayName("구버전 앱 호환: S3 전 응답 fixture 의 모든 칸이 같은 이름·같은 값으로 남는다(kind 는 여전히 UPCOMING/DEPARTED)")
    void legacyAppResponseShape() throws Exception {
        given(selectionService.candidates(1L, 7L, null)).willReturn(fourCandidates());
        ObjectMapper om = new ObjectMapper();
        JsonNode legacy = om.readTree(getClass().getResourceAsStream("/journey/train-candidates-legacy-v1.json"));

        String body = mockMvc.perform(get("/api/journeys/7/subway/train-candidates").with(authentication(user())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode now = om.readTree(body);

        assertThat(now).hasSize(legacy.size());
        for (int i = 0; i < legacy.size(); i++) {
            JsonNode old = legacy.get(i);
            JsonNode cur = now.get(i);
            old.fieldNames().forEachRemaining(field -> {
                JsonNode was = old.get(field);
                JsonNode is = cur.get(field);
                if (was.isNull()) {
                    assertThat(is == null || is.isNull()).as(field).isTrue();
                } else {
                    assertThat(is).as(field).isEqualTo(was);
                }
            });
        }
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
    @DisplayName("오류 코드: 404 꺼짐 / 409 대기 아님 / 422 후보 밖·추적 불가·미정차 / 409 개정본 / 400 본문")
    void errors() throws Exception {
        given(selectionService.select(eq(1L), eq(7L), anyString(), anyInt(), org.mockito.ArgumentMatchers.isNull()))
                .willThrow(new BusinessException(CommonErrorCode.NOT_FOUND))
                .willThrow(new BusinessException(JourneyErrorCode.NOT_WAITING_FOR_SUBWAY))
                .willThrow(new BusinessException(JourneyErrorCode.TRAIN_NOT_IN_CANDIDATES))
                .willThrow(new BusinessException(JourneyErrorCode.TRAIN_NOT_TRACKABLE))
                .willThrow(new BusinessException(JourneyErrorCode.TRAIN_SKIPS_STATION))
                .willThrow(new StaleRouteRevisionException(2));

        perform().andExpect(status().isNotFound());
        perform().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_WAITING_FOR_SUBWAY"));
        perform().andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRAIN_NOT_IN_CANDIDATES"));
        perform().andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRAIN_NOT_TRACKABLE"));
        perform().andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRAIN_SKIPS_STATION"));
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
