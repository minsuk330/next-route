package watoo.grd.nextroute.infrastructure.adapter.in.api.route.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.port.in.RecordSearchHistoryUseCase;
import watoo.grd.nextroute.application.route.port.in.SearchRouteUseCase;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * optional auth 배선 검증: 익명(userId=null)이면 이력 기록을 호출하지 않고, 로그인 사용자면 기록한다.
 * 검색 자체는 어느 쪽이든 동일하게 반환된다.
 */
@ExtendWith(MockitoExtension.class)
class RouteSearchControllerTest {

    @Mock SearchRouteUseCase searchRouteUseCase;
    @Mock RecordSearchHistoryUseCase recordSearchHistoryUseCase;

    private RouteSearchRequest request() {
        return new RouteSearchRequest(127.0, 37.5, 127.01, 37.51, "출발지", "도착지");
    }

    private RouteSearchResult result() {
        return new RouteSearchResult(2, 0, 0, 0, 0, List.of());
    }

    @Test
    void TC_익명_검색은_이력을_기록하지_않는다() {
        RouteSearchController controller =
                new RouteSearchController(searchRouteUseCase, recordSearchHistoryUseCase);
        RouteSearchRequest request = request();
        RouteSearchResult result = result();
        when(searchRouteUseCase.search(request)).thenReturn(result);

        var response = controller.search(null, request);

        assertThat(response.getBody()).isSameAs(result);
        verifyNoInteractions(recordSearchHistoryUseCase);
    }

    @Test
    void TC_로그인_검색은_이력을_기록한다() {
        RouteSearchController controller =
                new RouteSearchController(searchRouteUseCase, recordSearchHistoryUseCase);
        RouteSearchRequest request = request();
        RouteSearchResult result = result();
        when(searchRouteUseCase.search(request)).thenReturn(result);

        var response = controller.search(42L, request);

        assertThat(response.getBody()).isSameAs(result);
        verify(recordSearchHistoryUseCase).record(42L, request, result);
    }
}
