package watoo.grd.nextroute.application.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.route.dto.*;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.domain.route.log.entity.RouteSearchLog;
import watoo.grd.nextroute.domain.route.log.service.RouteDataService;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteSearchServiceTest {

    @Mock OdSayApiPort odSayApiPort;
    @Mock RouteDataService routeDataService;
    @Mock RoutePolylineEnricher polylineEnricher;
    @Mock WalkSegmentEnricher walkSegmentEnricher;
    @Mock TransferArrivalEnricher transferArrivalEnricher;

    RouteSearchService service;

    @BeforeEach
    void setUp() {
        service = new RouteSearchService(
                odSayApiPort, routeDataService, new ObjectMapper(),
                polylineEnricher, walkSegmentEnricher, transferArrivalEnricher);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private SubPathResult walkSubPath(List<CoordPoint> polyline, List<WalkStep> steps) {
        return new SubPathResult(
                3, 240, 320.0,
                Collections.emptyList(), Collections.emptyList(),
                "출발", "도착",
                127.0, 37.5, 127.01, 37.51,
                null, null, null, null, null,
                polyline,
                null, null, null, null, null, null,
                steps,
                null, null, null, null, null, null, null
        );
    }

    private SubPathResult subwaySubPath() {
        return new SubPathResult(
                1, 1200, 6000.0,
                Collections.emptyList(), Collections.emptyList(),
                "강남", "교대",
                127.0, 37.5, 127.01, 37.49,
                null, null, null, null, null,
                List.of(new CoordPoint(127.005, 37.499)),  // 지하철 polyline은 유지되어야 함
                null, null, null, null, null, null,
                null,
                null, null, null, null, null, null, null
        );
    }

    private RouteSearchResult resultWith(List<SubPathResult> subPaths) {
        PathInfo info = new PathInfo(2700, 1400, 640, 1, "출발역", "도착역", null);
        return new RouteSearchResult(0, 0, 1, 0, 0,
                List.of(new PathResult(3, info, subPaths, Collections.emptyList())));
    }

    private RouteSearchRequest request() {
        return new RouteSearchRequest(127.0, 37.5, 127.01, 37.51, "출발지", "도착지");
    }

    // ── 테스트 ────────────────────────────────────────────────────────────

    @Test
    void TC_정상_검색_흐름_enricher_호출순서() {
        RouteSearchResult odsayResult = resultWith(List.of(walkSubPath(null, null)));
        RouteSearchResult afterPolyline = resultWith(List.of(walkSubPath(null, null)));
        RouteSearchResult afterWalk = resultWith(List.of(
                walkSubPath(List.of(new CoordPoint(127.0, 37.5)),
                        List.of(new WalkStep(0, "SP", 200, "이동", 127.0, 37.5)))));
        RouteSearchResult afterTransfer = afterWalk;

        when(odSayApiPort.searchPath(127.0, 37.5, 127.01, 37.51)).thenReturn(odsayResult);
        when(polylineEnricher.enrich(odsayResult)).thenReturn(afterPolyline);
        when(walkSegmentEnricher.enrich(eq(afterPolyline), eq(127.0), eq(37.5), eq("출발지"),
                eq(127.01), eq(37.51), eq("도착지"))).thenReturn(afterWalk);
        when(transferArrivalEnricher.enrich(eq(afterWalk), any(Instant.class))).thenReturn(afterTransfer);

        RouteSearchResult result = service.search(request());

        var order = inOrder(odSayApiPort, polylineEnricher, walkSegmentEnricher, transferArrivalEnricher);
        order.verify(odSayApiPort).searchPath(127.0, 37.5, 127.01, 37.51);
        order.verify(polylineEnricher).enrich(odsayResult);
        order.verify(walkSegmentEnricher).enrich(any(), eq(127.0), eq(37.5), eq("출발지"),
                eq(127.01), eq(37.51), eq("도착지"));
        order.verify(transferArrivalEnricher).enrich(eq(afterWalk), any(Instant.class));

        assertThat(result).isSameAs(afterTransfer);
        SubPathResult walk = result.paths().get(0).subPaths().get(0);
        assertThat(walk.polyline()).hasSize(1);
        assertThat(walk.walkSteps()).hasSize(1);
    }

    @Test
    void TC_로그_저장_시_도보_polyline과_walkSteps_제외() throws Exception {
        SubPathResult enrichedWalk = walkSubPath(
                List.of(new CoordPoint(127.0, 37.5), new CoordPoint(127.01, 37.51)),
                List.of(new WalkStep(0, "SP", 200, "이동", 127.0, 37.5))
        );
        RouteSearchResult enriched = resultWith(List.of(enrichedWalk));

        when(odSayApiPort.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(enriched);
        when(polylineEnricher.enrich(any())).thenReturn(enriched);
        when(walkSegmentEnricher.enrich(any(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any())).thenReturn(enriched);
        when(transferArrivalEnricher.enrich(any(), any())).thenReturn(enriched);

        service.search(request());

        ArgumentCaptor<RouteSearchLog> logCaptor = ArgumentCaptor.forClass(RouteSearchLog.class);
        verify(routeDataService).save(logCaptor.capture());

        String json = logCaptor.getValue().getResponseJson();
        assertThat(json).contains("\"trafficType\":3");
        assertThat(json).contains("\"polyline\":null");
        assertThat(json).contains("\"walkSteps\":null");
    }

    @Test
    void TC_로그_저장_시_지하철_polyline은_유지() throws Exception {
        SubPathResult subway = subwaySubPath();
        RouteSearchResult enriched = resultWith(List.of(subway));

        when(odSayApiPort.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(enriched);
        when(polylineEnricher.enrich(any())).thenReturn(enriched);
        when(walkSegmentEnricher.enrich(any(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any())).thenReturn(enriched);
        when(transferArrivalEnricher.enrich(any(), any())).thenReturn(enriched);

        service.search(request());

        ArgumentCaptor<RouteSearchLog> logCaptor = ArgumentCaptor.forClass(RouteSearchLog.class);
        verify(routeDataService).save(logCaptor.capture());
        String json = logCaptor.getValue().getResponseJson();

        assertThat(json).contains("\"trafficType\":1");
        assertThat(json).contains("127.005");
        assertThat(json).contains("37.499");
    }

    @Test
    void TC_로그_저장_실패해도_검색_응답은_정상_반환() {
        RouteSearchResult enriched = resultWith(List.of(walkSubPath(null, null)));
        when(odSayApiPort.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(enriched);
        when(polylineEnricher.enrich(any())).thenReturn(enriched);
        when(walkSegmentEnricher.enrich(any(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any())).thenReturn(enriched);
        when(transferArrivalEnricher.enrich(any(), any())).thenReturn(enriched);
        doThrow(new RuntimeException("DB fail")).when(routeDataService).save(any());

        RouteSearchResult result = service.search(request());

        assertThat(result).isSameAs(enriched);
    }

}
