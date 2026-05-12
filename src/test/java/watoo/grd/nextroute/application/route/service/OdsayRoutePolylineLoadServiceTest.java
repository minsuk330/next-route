package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.dto.LaneGraphicResult;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.domain.route.polyline.service.OdsayRoutePolylineDataService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OdsayRoutePolylineLoadServiceTest {

    @Mock
    OdSayApiPort odSayApiPort;

    @Mock
    OdsayRoutePolylineDataService dataService;

    OdsayRoutePolylineLoadService service;

    @BeforeEach
    void setUp() {
        service = new OdsayRoutePolylineLoadService(odSayApiPort, dataService);
    }

    @Test
    void TC_정상_적재_성공() {
        List<CoordPoint> section = List.of(
                new CoordPoint(126.1, 37.1),
                new CoordPoint(126.2, 37.2),
                new CoordPoint(126.3, 37.3)
        );
        LaneGraphicResult lane = new LaneGraphicResult(2, 3, List.of(section));
        when(odSayApiPort.loadLane("0:0@3:2:-1:-1")).thenReturn(List.of(lane));

        OdsayRoutePolylineLoadService.LoadResult result = service.load("3", 2);

        assertThat(result.loaded()).isTrue();
        assertThat(result.pointCount()).isEqualTo(3);
        assertThat(result.sourceMapObject()).isEqualTo("0:0@3:2:-1:-1");
        verify(dataService).saveOrUpdatePolyline(eq("3"), eq(2), eq(3), any(), eq("0:0@3:2:-1:-1"));
    }

    @Test
    void TC_빈_lane_응답_실패_반환() {
        when(odSayApiPort.loadLane(anyString())).thenReturn(List.of());

        OdsayRoutePolylineLoadService.LoadResult result = service.load("3", 2);

        assertThat(result.loaded()).isFalse();
        assertThat(result.errorMessage()).contains("Empty lane");
        verify(dataService, never()).saveOrUpdatePolyline(any(), anyInt(), any(), any(), any());
    }

    @Test
    void TC_다중_section_flatten_순서_보존() {
        List<CoordPoint> s1 = List.of(new CoordPoint(126.1, 37.1), new CoordPoint(126.2, 37.2));
        List<CoordPoint> s2 = List.of(new CoordPoint(126.3, 37.3));
        LaneGraphicResult lane = new LaneGraphicResult(2, 3, List.of(s1, s2));
        when(odSayApiPort.loadLane(anyString())).thenReturn(List.of(lane));

        OdsayRoutePolylineLoadService.LoadResult result = service.load("5", 2);

        assertThat(result.loaded()).isTrue();
        assertThat(result.pointCount()).isEqualTo(3);
    }

    @Test
    void TC_ODsay_예외_시_실패_반환() {
        when(odSayApiPort.loadLane(anyString())).thenThrow(new RuntimeException("timeout"));

        OdsayRoutePolylineLoadService.LoadResult result = service.load("9", 2);

        assertThat(result.loaded()).isFalse();
        assertThat(result.errorMessage()).contains("timeout");
    }

    @Test
    void TC_mapObject_형식_검증() {
        when(odSayApiPort.loadLane("0:0@42:2:-1:-1")).thenReturn(List.of());

        service.load("42", 2);

        verify(odSayApiPort).loadLane("0:0@42:2:-1:-1");
    }
}
