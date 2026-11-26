package watoo.grd.nextroute.global.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.route.config.RouteSnapshotProperties;
import watoo.grd.nextroute.application.route.exception.OdSayApiException;
import watoo.grd.nextroute.application.route.port.in.RecordSearchHistoryUseCase;
import watoo.grd.nextroute.application.route.port.in.SearchRouteUseCase;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.application.route.service.RouteSearchEventRecorder;
import watoo.grd.nextroute.application.route.service.RouteSearchSnapshotRecorder;
import watoo.grd.nextroute.common.observability.SyntheticRequest;
import watoo.grd.nextroute.global.error.code.SubwayErrorCode;
import watoo.grd.nextroute.infrastructure.adapter.in.api.route.search.RouteSearchController;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 5xx 응답은 어느 핸들러에서 나왔든 ERROR 한 줄(스택 포함)이 남는다 — 장애 분석기의 재료 계약.
 */
@DisplayName("5xx 응답 로그 필터")
@ExtendWith(OutputCaptureExtension.class)
class ServerErrorLoggingFilterTest {

    private final ServerErrorLoggingFilter filter = new ServerErrorLoggingFilter();

    @RestController
    static class FailingController {
        @GetMapping("/test/unexpected")
        String unexpected() {
            throw new IllegalStateException("boom-unexpected");
        }

        @GetMapping("/test/unavailable")
        String unavailable() {
            throw new BusinessException(SubwayErrorCode.SUBWAY_ROUTE_UNAVAILABLE);
        }
    }

    private MockMvc routeSearchMvc(SearchRouteUseCase searchRouteUseCase) {
        var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        RouteSearchController controller = new RouteSearchController(
                searchRouteUseCase, mock(RecordSearchHistoryUseCase.class),
                new RouteSearchSnapshotRecorder(mock(RouteSearchSnapshotPort.class), new RouteSnapshotProperties(),
                        Clock.systemUTC()),
                factory.getBeanProvider(RouteSearchEventRecorder.class));
        return MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(filter)
                .build();
    }

    private MockMvc failingMvc() {
        return MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(filter)
                .build();
    }

    @Test
    @DisplayName("경로 검색 타임아웃(504)은 핸들러가 로그 없이 응답해도 예외와 스택이 남는다")
    void silentHandlerGatewayTimeoutIsLoggedWithStack(CapturedOutput output) throws Exception {
        SearchRouteUseCase useCase = mock(SearchRouteUseCase.class);
        when(useCase.search(any())).thenThrow(OdSayApiException.timeout("budget exceeded"));

        routeSearchMvc(useCase).perform(get("/api/route/search")
                        .param("startX", "126.97").param("startY", "37.55")
                        .param("endX", "127.02").param("endY", "37.49")
                        .header(SyntheticRequest.HEADER, "1"))
                .andExpect(status().isGatewayTimeout());

        assertThat(output.getAll())
                .contains("[ServerError] status=504 method=GET uri=/api/route/search pattern=/api/route/search synthetic=true")
                .contains("exception=" + OdSayApiException.class.getName() + ": budget exceeded")
                .contains("at watoo.grd.nextroute");
    }

    @Test
    @DisplayName("BusinessException 503 도 스택과 함께 남는다")
    void businessServiceUnavailableIsLogged(CapturedOutput output) throws Exception {
        failingMvc().perform(get("/test/unavailable")).andExpect(status().isServiceUnavailable());

        assertThat(output.getAll())
                .contains("[ServerError] status=503 method=GET uri=/test/unavailable pattern=/test/unavailable synthetic=false")
                .contains("exception=" + BusinessException.class.getName());
    }

    @Test
    @DisplayName("처리 안 된 예외는 GlobalExceptionHandler 가 이미 스택을 남기므로 필터는 다시 찍지 않는다")
    void alreadyLoggedUnexpectedExceptionIsNotDuplicated(CapturedOutput output) throws Exception {
        failingMvc().perform(get("/test/unexpected")).andExpect(status().isInternalServerError());

        assertThat(output.getAll()).contains("Unhandled exception. method=GET, path=/test/unexpected")
                .contains("boom-unexpected")
                .doesNotContain("[ServerError]");
    }

    @Test
    @DisplayName("예외 없이 5xx 를 쓴 응답도 한 줄 남긴다")
    void serverErrorWithoutExceptionIsLogged(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/raw");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> ((MockHttpServletResponse) res).setStatus(502));

        assertThat(output.getAll()).contains("[ServerError] status=502 method=GET uri=/raw pattern=null synthetic=false exception=none");
    }

    @Test
    @DisplayName("4xx 와 2xx 는 남기지 않는다")
    void nonServerErrorsAreNotLogged(CapturedOutput output) throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/a"), new MockHttpServletResponse(),
                (req, res) -> ((MockHttpServletResponse) res).setStatus(404));
        filter.doFilter(new MockHttpServletRequest("GET", "/b"), new MockHttpServletResponse(), (req, res) -> { });

        assertThat(output.getAll()).doesNotContain("[ServerError]");
    }
}
