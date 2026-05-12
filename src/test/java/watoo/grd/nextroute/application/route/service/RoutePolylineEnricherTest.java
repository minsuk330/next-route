package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.route.dto.*;
import watoo.grd.nextroute.domain.route.polyline.service.OdsayRoutePolylineDataService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoutePolylineEnricherTest {

    @Mock
    OdsayRoutePolylineDataService dataService;

    OdsayMapObjParser parser;
    OdsayRoutePolylineSlicer slicer;
    RoutePolylineEnricher enricher;

    @BeforeEach
    void setUp() {
        parser = new OdsayMapObjParser();
        slicer = new OdsayRoutePolylineSlicer();
        enricher = new RoutePolylineEnricher(parser, slicer, dataService);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    /** 역 A, B, C가 각각 2개씩 occurrence를 갖는 polyline */
    private OdsayRoutePolylineData stationPolyline() {
        List<OdsayRoutePolylinePoint> points = new ArrayList<>();
        points.add(new OdsayRoutePolylinePoint(0, 126.10, 37.10)); // A 도착
        points.add(new OdsayRoutePolylinePoint(1, 126.10, 37.10)); // A 출발
        points.add(new OdsayRoutePolylinePoint(2, 126.12, 37.12));
        points.add(new OdsayRoutePolylinePoint(3, 126.14, 37.14));
        points.add(new OdsayRoutePolylinePoint(4, 126.16, 37.16)); // B 도착
        points.add(new OdsayRoutePolylinePoint(5, 126.16, 37.16)); // B 출발
        points.add(new OdsayRoutePolylinePoint(6, 126.18, 37.18));
        points.add(new OdsayRoutePolylinePoint(7, 126.20, 37.20)); // C 도착
        points.add(new OdsayRoutePolylinePoint(8, 126.20, 37.20)); // C 출발
        return new OdsayRoutePolylineData(points);
    }

    private OdsayRoutePolylineData circularPolyline() {
        List<OdsayRoutePolylinePoint> points = new ArrayList<>();
        points.add(new OdsayRoutePolylinePoint(0, 126.10, 37.10)); // A 도착
        points.add(new OdsayRoutePolylinePoint(1, 126.10, 37.10)); // A 출발
        points.add(new OdsayRoutePolylinePoint(2, 126.15, 37.15));
        points.add(new OdsayRoutePolylinePoint(3, 126.20, 37.20)); // B 도착
        points.add(new OdsayRoutePolylinePoint(4, 126.20, 37.20)); // B 출발
        points.add(new OdsayRoutePolylinePoint(5, 126.25, 37.25));
        points.add(new OdsayRoutePolylinePoint(6, 126.30, 37.30)); // C 도착
        points.add(new OdsayRoutePolylinePoint(7, 126.30, 37.30)); // C 출발
        points.add(new OdsayRoutePolylinePoint(8, 126.35, 37.35));
        points.add(new OdsayRoutePolylinePoint(9, 126.40, 37.40)); // D 도착
        points.add(new OdsayRoutePolylinePoint(10, 126.40, 37.40)); // D 출발
        points.add(new OdsayRoutePolylinePoint(11, 126.45, 37.45));
        points.add(new OdsayRoutePolylinePoint(12, 126.50, 37.50)); // E 도착
        points.add(new OdsayRoutePolylinePoint(13, 126.50, 37.50)); // E 출발
        points.add(new OdsayRoutePolylinePoint(14, 126.05, 37.05));
        return new OdsayRoutePolylineData(points);
    }

    private RouteSearchResult searchResult(String mapObj, SubPathResult subPath) {
        PathInfo info = new PathInfo(25, 1400, 300, 1, "출발역", "도착역", mapObj);
        PathResult path = new PathResult(1, info, List.of(subPath), Collections.emptyList());
        return new RouteSearchResult(0, 0, 1, 0, 0, List.of(path));
    }

    private SubPathResult subwaySubPath(double startX, double startY, double endX, double endY,
                                        String startName, String endName) {
        return subwaySubPath(startX, startY, endX, endY, startName, endName, Collections.emptyList());
    }

    private SubPathResult subwaySubPath(double startX, double startY, double endX, double endY,
                                        String startName, String endName,
                                        List<StationResult> stations) {
        return new SubPathResult(
                1, 1200, 6000.0,
                List.of(new LaneResult("2호선", null, 2, null)),
                stations,
                startName, endName,
                startX, startY, endX, endY,
                null, null, null, null, null,
                null,
                null, null, null,
                null, null, null,
                null
        );
    }

    // ── 테스트 ────────────────────────────────────────────────────────────

    @Test
    void TC_DB_hit_정방향_좌표_slice_주입() {
        // mapObj는 routeId 추출에만 쓰임. start/end 값(812/814)은 무시되고 좌표로 slice.
        when(dataService.findPolyline("8", 2)).thenReturn(Optional.of(stationPolyline()));

        SubPathResult sp = subwaySubPath(126.10, 37.10, 126.16, 37.16, "A역", "B역");
        RouteSearchResult result = enricher.enrich(searchResult("8:2:812:814", sp));

        List<CoordPoint> polyline = result.paths().get(0).subPaths().get(0).polyline();
        assertThat(polyline).hasSize(4);
        assertThat(polyline.get(0).x()).isEqualTo(126.10);
        assertThat(polyline.get(3).x()).isEqualTo(126.16);
    }

    @Test
    void TC_DB_hit_역방향_좌표_slice_후_reverse() {
        when(dataService.findPolyline("8", 2)).thenReturn(Optional.of(stationPolyline()));

        // C → A (역방향)
        SubPathResult sp = subwaySubPath(126.20, 37.20, 126.10, 37.10, "C역", "A역");
        RouteSearchResult result = enricher.enrich(searchResult("8:2:812:814", sp));

        List<CoordPoint> polyline = result.paths().get(0).subPaths().get(0).polyline();
        assertThat(polyline).isNotEmpty();
        // 진행 방향 첫 좌표 = C
        assertThat(polyline.get(0).x()).isEqualTo(126.20);
        // 진행 방향 마지막 좌표 = A
        assertThat(polyline.get(polyline.size() - 1).x()).isEqualTo(126.10);
    }

    @Test
    void TC_큰_mapObj_ref_여도_좌표_slice는_정상() {
        // mapObj=8:2:812:814 (큰 ref) + cache size=9 → 좌표 기반이라 OOB 없음
        when(dataService.findPolyline("8", 2)).thenReturn(Optional.of(stationPolyline()));

        SubPathResult sp = subwaySubPath(126.10, 37.10, 126.20, 37.20, "A역", "C역");
        RouteSearchResult result = enricher.enrich(searchResult("8:2:812:814", sp));

        List<CoordPoint> polyline = result.paths().get(0).subPaths().get(0).polyline();
        assertThat(polyline).hasSize(7); // A 출발(1) ~ C 도착(7)
    }

    @Test
    void TC_순환선은_passStopList_두번째_역으로_wrap_방향을_선택한다() {
        when(dataService.findPolyline("2", 2)).thenReturn(Optional.of(circularPolyline()));

        List<StationResult> stations = List.of(
                new StationResult(0, "D", "D역", 126.40, 37.40),
                new StationResult(1, "E", "E역", 126.50, 37.50),
                new StationResult(2, "A", "A역", 126.10, 37.10),
                new StationResult(3, "B", "B역", 126.20, 37.20)
        );
        SubPathResult sp = subwaySubPath(126.40, 37.40, 126.20, 37.20, "D역", "B역", stations);
        RouteSearchResult result = enricher.enrich(searchResult("2:2:812:814", sp));

        List<CoordPoint> polyline = result.paths().get(0).subPaths().get(0).polyline();
        assertThat(polyline).isNotEmpty();
        assertThat(polyline).extracting(CoordPoint::x).contains(126.50, 126.10, 126.20);
        assertThat(polyline).extracting(CoordPoint::x).doesNotContain(126.30);
    }

    @Test
    void TC_DB_miss_시_빈_polyline_반환하고_collection_job_등록된다() {
        when(dataService.findPolyline("99", 2)).thenReturn(Optional.empty());

        SubPathResult sp = subwaySubPath(126.10, 37.10, 126.16, 37.16, "A역", "B역");
        RouteSearchResult result = enricher.enrich(searchResult("99:2:0:0", sp));

        List<CoordPoint> polyline = result.paths().get(0).subPaths().get(0).polyline();
        assertThat(polyline).isEmpty();
        verify(dataService).requestCollection(eq("99"), eq(2), anyString());
    }

    @Test
    void TC_좌표_null_시_빈_polyline_반환_job_등록_없음() {
        when(dataService.findPolyline("8", 2)).thenReturn(Optional.of(stationPolyline()));

        // startX null
        SubPathResult sp = new SubPathResult(
                1, 1200, 6000.0,
                List.of(new LaneResult("2호선", null, 2, null)),
                Collections.emptyList(),
                "A역", "B역",
                null, 37.10, 126.16, 37.16,
                null, null, null, null, null,
                null,
                null, null, null,
                null, null, null,
                null
        );
        RouteSearchResult result = enricher.enrich(searchResult("8:2:0:0", sp));

        List<CoordPoint> polyline = result.paths().get(0).subPaths().get(0).polyline();
        assertThat(polyline).isEmpty();
        verify(dataService, never()).requestCollection(anyString(), anyInt(), anyString());
    }

    @Test
    void TC_좌표_미발견_시_빈_polyline() {
        when(dataService.findPolyline("8", 2)).thenReturn(Optional.of(stationPolyline()));

        // 어디에도 없는 좌표
        SubPathResult sp = subwaySubPath(999.0, 999.0, 998.0, 998.0, "X역", "Y역");
        RouteSearchResult result = enricher.enrich(searchResult("8:2:0:0", sp));

        List<CoordPoint> polyline = result.paths().get(0).subPaths().get(0).polyline();
        assertThat(polyline).isEmpty();
    }

    @Test
    void TC_복수_routeId_miss_시_각각_job_등록된다() {
        when(dataService.findPolyline(anyString(), anyInt())).thenReturn(Optional.empty());

        SubPathResult sp = subwaySubPath(126.10, 37.10, 126.16, 37.16, "A역", "B역");
        enricher.enrich(searchResult("3:2:0:0@17:2:0:0", sp));

        verify(dataService).requestCollection(eq("3"), eq(2), anyString());
        verify(dataService).requestCollection(eq("17"), eq(2), anyString());
    }

    @Test
    void TC_같은_routeId_miss_시_job_등록_1회만() {
        when(dataService.findPolyline("3", 2)).thenReturn(Optional.empty());

        SubPathResult sp = subwaySubPath(126.10, 37.10, 126.16, 37.16, "A역", "B역");
        enricher.enrich(searchResult("3:2:0:5@3:2:10:20", sp));

        verify(dataService, times(1)).findPolyline("3", 2);
        verify(dataService, times(1)).requestCollection(eq("3"), eq(2), anyString());
    }

    @Test
    void TC_버스_fragment는_무시된다() {
        SubPathResult sp = subwaySubPath(126.10, 37.10, 126.16, 37.16, "A역", "B역");
        enricher.enrich(searchResult("5:1:0:0", sp));

        verifyNoInteractions(dataService);
    }

    @Test
    void TC_mapObj_null_시_원본_그대로_반환() {
        SubPathResult sp = subwaySubPath(126.10, 37.10, 126.16, 37.16, "A역", "B역");
        RouteSearchResult original = searchResult(null, sp);

        RouteSearchResult result = enricher.enrich(original);

        assertThat(result).isEqualTo(original);
        verifyNoInteractions(dataService);
    }

    @Test
    void TC_검색_중_loadLane_호출_없음() {
        when(dataService.findPolyline("8", 2)).thenReturn(Optional.of(stationPolyline()));

        SubPathResult sp = subwaySubPath(126.10, 37.10, 126.16, 37.16, "A역", "B역");
        enricher.enrich(searchResult("8:2:0:0", sp));

        verify(dataService).findPolyline("8", 2);
        verify(dataService, never()).requestCollection(anyString(), anyInt(), anyString());
    }
}
