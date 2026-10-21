package watoo.grd.nextroute.infrastructure.adapter.in.api.route.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.auth.config.AdminProperties;
import watoo.grd.nextroute.application.auth.port.out.TokenGenerationPort;
import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.port.in.RecordSearchHistoryUseCase;
import watoo.grd.nextroute.application.route.port.in.SearchRouteUseCase;
import watoo.grd.nextroute.application.route.service.RouteSearchSnapshotRecorder;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET 쿼리 바인딩(subway-express S2): {@code excludeExpress} 가 요청 레코드로 들어온다. 없으면 null — 급행 포함(옛 동작).
 */
@WebMvcTest(RouteSearchController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class RouteSearchControllerBindingTest {

    @Autowired MockMvc mockMvc;
    @MockBean SearchRouteUseCase searchRouteUseCase;
    @MockBean RecordSearchHistoryUseCase recordSearchHistoryUseCase;
    @MockBean RouteSearchSnapshotRecorder snapshotRecorder;
    @MockBean JwtProvider jwtProvider;
    @MockBean TokenGenerationPort tokenGenerationPort;
    @MockBean AdminProperties adminProperties;

    private RouteSearchRequest searchWith(String extraQuery) throws Exception {
        RouteSearchResult empty = new RouteSearchResult(0, 0, 0, 0, 0, List.of());
        given(searchRouteUseCase.search(any())).willReturn(empty);
        given(snapshotRecorder.attach(any(), any())).willReturn(empty);

        mockMvc.perform(get("/api/route/search?startX=127.0&startY=37.5&endX=127.01&endY=37.51" + extraQuery))
                .andExpect(status().isOk());

        ArgumentCaptor<RouteSearchRequest> captor = ArgumentCaptor.forClass(RouteSearchRequest.class);
        verify(searchRouteUseCase).search(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("excludeExpress=true 가 요청에 실린다")
    void bindsExcludeExpress() throws Exception {
        RouteSearchRequest request = searchWith("&excludeExpress=true");

        assertThat(request.excludeExpress()).isTrue();
        assertThat(request.excludesExpress()).isTrue();
    }

    @Test
    @DisplayName("없으면 null 이고 급행 포함으로 읽는다(구버전 앱)")
    void absentMeansIncludeExpress() throws Exception {
        RouteSearchRequest request = searchWith("");

        assertThat(request.excludeExpress()).isNull();
        assertThat(request.excludesExpress()).isFalse();
        assertThat(request.startX()).isEqualTo(127.0);
    }
}
