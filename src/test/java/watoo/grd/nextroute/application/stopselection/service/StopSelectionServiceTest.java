package watoo.grd.nextroute.application.stopselection.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.route.service.PredictionSupportService;
import watoo.grd.nextroute.application.stopselection.dto.RouteStopsResult;
import watoo.grd.nextroute.application.stopselection.dto.SearchSuggestResult;
import watoo.grd.nextroute.application.stopselection.dto.StopRouteResult;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.entity.BusStop;
import watoo.grd.nextroute.domain.bus.repository.RouteStopProjection;
import watoo.grd.nextroute.domain.bus.repository.StopRouteProjection;
import watoo.grd.nextroute.domain.bus.service.BusDataService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StopSelectionServiceTest {

    @Mock BusDataService busDataService;
    @Mock PredictionSupportService predictionSupportService;
    @InjectMocks StopSelectionService service;

    @Test
    @DisplayName("정류장 경유 노선: 지원 노선만 supportsPrediction=true")
    void getStopRoutes_marksSupport() {
        given(busDataService.findRoutesByStopId("1001")).willReturn(List.of(
                stopRouteProjection("R1", "146", "강남방면", 3, "기점A", "종점A"),
                stopRouteProjection("R2", "341", "잠실방면", 4, "기점B", "종점B")
        ));
        given(predictionSupportService.isSupported("R1")).willReturn(true);
        given(predictionSupportService.isSupported("R2")).willReturn(false);

        List<StopRouteResult> result = service.getStopRoutes("1001");

        assertThat(result).containsExactly(
                new StopRouteResult("R1", "146", "강남방면", 3, "기점A", "종점A", true),
                new StopRouteResult("R2", "341", "잠실방면", 4, "기점B", "종점B", false)
        );
    }

    @Test
    @DisplayName("노선 경유 정류장: 좌표 null 허용, 노선 단위 배지")
    void getRouteStops_routeLevelBadge() {
        given(busDataService.findStopsByRouteId("R1")).willReturn(List.of(
                routeStopProjection(1, "1001", "시청앞", 37.5, 127.0, "강남방면"),
                routeStopProjection(2, "1002", "좌표없음", null, null, "강남방면")
        ));
        given(predictionSupportService.isSupported("R1")).willReturn(true);

        RouteStopsResult result = service.getRouteStops("R1");

        assertThat(result.routeId()).isEqualTo("R1");
        assertThat(result.supportsPrediction()).isTrue();
        assertThat(result.stops()).containsExactly(
                new RouteStopsResult.RouteStop(1, "1001", "시청앞", 37.5, 127.0, "강남방면"),
                new RouteStopsResult.RouteStop(2, "1002", "좌표없음", null, null, "강남방면")
        );
    }

    @Test
    @DisplayName("자동완성: 버스번호 prefix + 정류장명 혼합, 배지 채움")
    void suggest_mixesRoutesAndStops() {
        given(busDataService.searchRoutesByNamePrefix("14")).willReturn(List.of(
                busRoute("R1", "143", 3, "기점", "종점")
        ));
        given(busDataService.searchStopsByNamePrefix("14")).willReturn(List.of(
                busStop("1001", "14번가", "01-001", 37.5, 127.0)
        ));
        given(predictionSupportService.isSupported("R1")).willReturn(true);
        given(busDataService.findStopsByRouteId("R1")).willReturn(List.of());

        SearchSuggestResult result = service.suggest("14");

        assertThat(result.routes()).containsExactly(
                new SearchSuggestResult.SuggestRoute("R1", "143", 3, "기점", "종점", true));
        assertThat(result.stops()).containsExactly(
                new SearchSuggestResult.SuggestStop("1001", "14번가", "01-001", 37.5, 127.0));
    }

    @Test
    @DisplayName("자동완성: route 단일 매치 시 경유 정류장 전체 embed (seq 순, 좌표 null 허용)")
    void suggest_singleRouteMatch_embedsStops() {
        given(busDataService.searchRoutesByNamePrefix("143")).willReturn(List.of(
                busRoute("R1", "143", 3, "기점", "종점")
        ));
        given(busDataService.searchStopsByNamePrefix("143")).willReturn(List.of());
        given(predictionSupportService.isSupported("R1")).willReturn(true);
        given(busDataService.findStopsByRouteId("R1")).willReturn(List.of(
                routeStopProjection(1, "1001", "시청앞", 37.5, 127.0, "강남방면"),
                routeStopProjection(2, "1002", "좌표없음", null, null, "강남방면")
        ));

        SearchSuggestResult result = service.suggest("143");

        assertThat(result.routeStops()).containsExactly(
                new RouteStopsResult.RouteStop(1, "1001", "시청앞", 37.5, 127.0, "강남방면"),
                new RouteStopsResult.RouteStop(2, "1002", "좌표없음", null, null, "강남방면"));
    }

    @Test
    @DisplayName("자동완성: route 다중 매치 시 routeStops 빈 리스트, 정류장 조회 안 함")
    void suggest_multipleRouteMatch_noStops() {
        given(busDataService.searchRoutesByNamePrefix("14")).willReturn(List.of(
                busRoute("R1", "143", 3, "기점", "종점"),
                busRoute("R2", "146", 3, "기점", "종점")
        ));
        given(busDataService.searchStopsByNamePrefix("14")).willReturn(List.of());
        lenient().when(predictionSupportService.isSupported(org.mockito.ArgumentMatchers.any())).thenReturn(false);

        SearchSuggestResult result = service.suggest("14");

        assertThat(result.routes()).hasSize(2);
        assertThat(result.routeStops()).isEmpty();
        verify(busDataService, never()).findStopsByRouteId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("자동완성: 공백/빈/null keyword는 빈 결과, DB 조회 안 함")
    void suggest_blankKeywordReturnsEmpty() {
        assertThat(service.suggest("   ").routes()).isEmpty();
        assertThat(service.suggest("").stops()).isEmpty();
        assertThat(service.suggest(null).routes()).isEmpty();

        verify(busDataService, never()).searchRoutesByNamePrefix(org.mockito.ArgumentMatchers.any());
        verify(busDataService, never()).searchStopsByNamePrefix(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("자동완성: 최대 길이(20) 초과 keyword는 빈 결과")
    void suggest_tooLongKeywordReturnsEmpty() {
        String tooLong = "a".repeat(21);

        SearchSuggestResult result = service.suggest(tooLong);

        assertThat(result.routes()).isEmpty();
        assertThat(result.stops()).isEmpty();
        verify(busDataService, never()).searchRoutesByNamePrefix(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("자동완성: keyword는 trim 후 조회")
    void suggest_trimsKeyword() {
        given(busDataService.searchRoutesByNamePrefix("146")).willReturn(List.of());
        given(busDataService.searchStopsByNamePrefix("146")).willReturn(List.of());

        service.suggest("  146  ");

        verify(busDataService).searchRoutesByNamePrefix("146");
        verify(busDataService).searchStopsByNamePrefix("146");
    }

    @Test
    @DisplayName("정류장 없으면 빈 리스트 (404 아님)")
    void getStopRoutes_emptyWhenUnknown() {
        given(busDataService.findRoutesByStopId("nope")).willReturn(List.of());

        assertThat(service.getStopRoutes("nope")).isEmpty();
    }

    // ===== fixtures =====

    private StopRouteProjection stopRouteProjection(String routeId, String routeName, String direction,
                                                    Integer routeType, String start, String end) {
        return new StopRouteProjection() {
            @Override public String getRouteId() { return routeId; }
            @Override public String getRouteName() { return routeName; }
            @Override public String getDirection() { return direction; }
            @Override public Integer getRouteType() { return routeType; }
            @Override public String getStartStation() { return start; }
            @Override public String getEndStation() { return end; }
        };
    }

    private RouteStopProjection routeStopProjection(Integer seq, String stopId, String stopName,
                                                    Double lat, Double lng, String direction) {
        return new RouteStopProjection() {
            @Override public Integer getSeq() { return seq; }
            @Override public String getStopId() { return stopId; }
            @Override public String getStopName() { return stopName; }
            @Override public Double getLatitude() { return lat; }
            @Override public Double getLongitude() { return lng; }
            @Override public String getDirection() { return direction; }
        };
    }

    private BusRoute busRoute(String routeId, String routeName, Integer routeType, String start, String end) {
        return BusRoute.builder()
                .routeId(routeId).routeName(routeName).routeType(routeType)
                .startStation(start).endStation(end)
                .build();
    }

    private BusStop busStop(String stopId, String stopName, String arsId, Double lat, Double lng) {
        return BusStop.builder()
                .stopId(stopId).stopName(stopName).arsId(arsId)
                .latitude(lat).longitude(lng)
                .build();
    }
}
