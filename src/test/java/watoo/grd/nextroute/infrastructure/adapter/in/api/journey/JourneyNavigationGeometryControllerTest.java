package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

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
import watoo.grd.nextroute.application.auth.port.out.TokenGenerationPort;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeoPoint;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeometryQuality;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.ManeuverGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.SegmentGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.StopGeometry;
import watoo.grd.nextroute.application.journey.dto.ManeuverType;
import watoo.grd.nextroute.application.journey.exception.StaleRouteRevisionException;
import watoo.grd.nextroute.application.journey.port.in.GetJourneyNavigationGeometryUseCase;
import watoo.grd.nextroute.application.auth.config.AdminProperties;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.GlobalExceptionHandler;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 여정 좌표 API 의 상태 코드 계약과 보호 여부를 고정한다.
 *
 * <p>{@code SecurityConfig} 는 "적어 둔 경로만 보호"라 규칙을 빠뜨리면 익명에 열린다.
 */
@WebMvcTest(JourneyNavigationGeometryController.class)
@Import({SecurityConfig.class, CorsConfig.class,
        GlobalExceptionHandler.class, JourneyControllerAdvice.class})
class JourneyNavigationGeometryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean GetJourneyNavigationGeometryUseCase getNavigationGeometryUseCase;
    @MockBean JwtProvider jwtProvider;
    @MockBean TokenGenerationPort tokenGenerationPort;
    @MockBean AdminProperties adminProperties;

    @Test
    @DisplayName("200 — 단계별 좌표와 구간을 돌려준다")
    void returnsGeometry() throws Exception {
        given(getNavigationGeometryUseCase.get(eq(1L), eq(7L), any())).willReturn(geometry());

        mockMvc.perform(get("/api/journeys/7/navigation-geometry")
                        .with(authentication(authUser(1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journeyId").value(7))
                .andExpect(jsonPath("$.routeRevision").value(1))
                .andExpect(jsonPath("$.legs[0].type").value("RIDE_BUS"))
                .andExpect(jsonPath("$.legs[0].quality").value("OK"))
                .andExpect(jsonPath("$.legs[0].segments[0].sectionId").value("s1"))
                .andExpect(jsonPath("$.legs[0].segments[0].geometrySegmentIndex").value(0))
                .andExpect(jsonPath("$.legs[0].stops[0].seq").value(10))
                .andExpect(jsonPath("$.legs[0].stops[0].stopName").value("승차 정류장"))
                // #266 정류장 번호(서울 노선표 경로만), 모르면 null
                .andExpect(jsonPath("$.legs[0].stops[0].stopNo").value("05229"))
                .andExpect(jsonPath("$.legs[0].stops[1].stopNo").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.legs[0].stops[1].seq").value(11))
                .andExpect(jsonPath("$.legs[0].stops[1].x").value((Object) null))
                .andExpect(jsonPath("$.legs[0].stops[1].y").value((Object) null))
                // 안내점 없는 단계도 필드를 빼지 않는다. 앱이 null 을 따로 다루지 않아야 한다.
                .andExpect(jsonPath("$.legs[0].maneuvers").isArray())
                .andExpect(jsonPath("$.legs[0].maneuvers").isEmpty());
    }

    @Test
    @DisplayName("200 — 걷기 단계는 안내점을 필드째 내려준다")
    void exposesWalkManeuvers() throws Exception {
        given(getNavigationGeometryUseCase.get(eq(1L), eq(7L), any())).willReturn(walkGeometry());

        mockMvc.perform(get("/api/journeys/7/navigation-geometry")
                        .with(authentication(authUser(1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legs[0].type").value("WALK"))
                .andExpect(jsonPath("$.legs[0].maneuvers", hasSize(2)))
                .andExpect(jsonPath("$.legs[0].maneuvers[0].maneuverIndex").value(0))
                .andExpect(jsonPath("$.legs[0].maneuvers[0].type").value("DEPART"))
                .andExpect(jsonPath("$.legs[0].maneuvers[0].instruction").value("출발"))
                .andExpect(jsonPath("$.legs[0].maneuvers[1].maneuverIndex").value(1))
                .andExpect(jsonPath("$.legs[0].maneuvers[1].type").value("LEFT"))
                .andExpect(jsonPath("$.legs[0].maneuvers[1].instruction")
                        .value("횡단보도를 건넌 후 좌회전하세요"))
                .andExpect(jsonPath("$.legs[0].maneuvers[1].geometrySegmentIndex").value(15))
                .andExpect(jsonPath("$.legs[0].maneuvers[1].segmentProgress").value(0.42))
                .andExpect(jsonPath("$.legs[0].maneuvers[1].distanceFromLegStartMeters").value(184.6))
                .andExpect(jsonPath("$.legs[0].maneuvers[1].x").value(127.1021))
                .andExpect(jsonPath("$.legs[0].maneuvers[1].y").value(37.5134));
    }

    @Test
    @DisplayName("404 — 없는 여정과 남의 여정을 구분하지 않는다")
    void notFound() throws Exception {
        given(getNavigationGeometryUseCase.get(any(), any(), any()))
                .willThrow(new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));

        mockMvc.perform(get("/api/journeys/7/navigation-geometry")
                        .with(authentication(authUser(1L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOURNEY_NOT_FOUND"));
    }

    @Test
    @DisplayName("409 — 이전 개정본이면 현재 개정본을 details 로 함께 준다")
    void staleRevision() throws Exception {
        given(getNavigationGeometryUseCase.get(any(), any(), any()))
                .willThrow(new StaleRouteRevisionException(3));

        mockMvc.perform(get("/api/journeys/7/navigation-geometry?routeRevision=2")
                        .with(authentication(authUser(1L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_ROUTE_REVISION"))
                .andExpect(jsonPath("$.details").value(3));
    }

    @Test
    @DisplayName("401 — 인증 없이 부르면 막는다")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/journeys/7/navigation-geometry"))
                .andExpect(status().isUnauthorized());
    }

    private Authentication authUser(Long userId) {
        return new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private JourneyNavigationGeometry geometry() {
        SegmentGeometry segment = new SegmentGeometry(0, "s1", 10, 11, 420.0, 60,
                GeometryQuality.OK, null,
                List.of(new GeoPoint(127.0, 37.5), new GeoPoint(127.004, 37.5)));
        LegGeometry leg = new LegGeometry(2, JourneyLegType.RIDE_BUS, "R1", 1200, 420.0,
                GeometryQuality.OK,
                List.of(new GeoPoint(127.0, 37.5), new GeoPoint(127.004, 37.5)),
                List.of(segment),
                List.of(
                        new StopGeometry(10, "S10", "승차 정류장", 127.0, 37.5, "05229"),
                        new StopGeometry(11, "S11", "하차 정류장", null, null)));
        return new JourneyNavigationGeometry(7L, 1, List.of(leg), null);
    }

    /** 걷기 단계 하나. 안내점 필드가 실제 JSON 에 나오는지 보기 위한 값이다. */
    private JourneyNavigationGeometry walkGeometry() {
        SegmentGeometry segment = new SegmentGeometry(15, null, null, null, 40.0, 30,
                GeometryQuality.OK, null,
                List.of(new GeoPoint(127.1020, 37.5133), new GeoPoint(127.1022, 37.5135)));
        LegGeometry leg = new LegGeometry(0, JourneyLegType.WALK, null, 300, 320.0,
                GeometryQuality.OK,
                List.of(new GeoPoint(127.1020, 37.5133), new GeoPoint(127.1022, 37.5135)),
                List.of(segment),
                List.of(),
                List.of(
                        new ManeuverGeometry(0, ManeuverType.DEPART, "출발", 0, 0.0, 0.0,
                                127.1000, 37.5120),
                        new ManeuverGeometry(1, ManeuverType.LEFT, "횡단보도를 건넌 후 좌회전하세요",
                                15, 0.42, 184.6, 127.1021, 37.5134)));
        return new JourneyNavigationGeometry(7L, 1, List.of(leg), null);
    }
}
