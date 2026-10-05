package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineSection;
import watoo.grd.nextroute.application.bus.port.out.BusRouteSegmentPolylinePort;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineData.QualityStatus;
import watoo.grd.nextroute.domain.bus.service.BusRoutePolylineData;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusRouteShapeEnricherTest {

    private final TransferStopResolver resolver = mock(TransferStopResolver.class);
    private final BusRouteSegmentPolylinePort polylinePort = mock(BusRouteSegmentPolylinePort.class);
    private final BusRouteShapeEnricher enricher = new BusRouteShapeEnricher(resolver, polylinePort);

    private static final List<CoordPoint> OTP_SHAPE =
            List.of(new CoordPoint(127.0, 37.5), new CoordPoint(127.02, 37.5));

    @BeforeEach
    void setUp() {
        when(resolver.resolveRoute("BL370", "370")).thenReturn(Optional.of(
                new TransferStopResolver.RouteResolution("R1", "370", false, null)));
        when(resolver.resolveStop("100")).thenReturn(Optional.of(new TransferStopResolver.StopResolution("S100")));
        when(resolver.resolveStop("200")).thenReturn(Optional.of(new TransferStopResolver.StopResolution("S200")));
        when(resolver.resolveSeq("R1", "S100")).thenReturn(new TransferStopResolver.SeqResolution(List.of(10)));
        when(resolver.resolveSeq("R1", "S200")).thenReturn(new TransferStopResolver.SeqResolution(List.of(12)));
    }

    @Test
    @DisplayName("노선·순번이 해석되면 안내 지도와 같은 정류장 구간 좌표로 바꾼다")
    void replacesBusShapeWithSectionPolyline() {
        when(polylinePort.findSections("R1", 10, 12)).thenReturn(List.of(
                section(10, 11, QualityStatus.OK, point(127.0, 37.5), point(127.005, 37.501), point(127.01, 37.5)),
                section(11, 12, QualityStatus.REVIEW, point(127.01, 37.5), point(127.02, 37.5))));

        SubPathResult bus = enrichedBus(search(bus()));

        assertThat(bus.polyline()).containsExactly(
                new CoordPoint(127.0, 37.5), new CoordPoint(127.005, 37.501),
                new CoordPoint(127.01, 37.5), new CoordPoint(127.02, 37.5));
    }

    @Test
    @DisplayName("구간 좌표가 없으면 엔진 형상을 그대로 둔다")
    void keepsEngineShapeWithoutSections() {
        when(polylinePort.findSections("R1", 10, 12)).thenReturn(List.of());

        assertThat(enrichedBus(search(bus())).polyline()).isEqualTo(OTP_SHAPE);
    }

    @Test
    @DisplayName("순번이 여러 개(순환 노선)면 안내도 형상을 못 찾으므로 바꾸지 않는다")
    void keepsShapeWhenSeqAmbiguous() {
        when(resolver.resolveSeq("R1", "S100")).thenReturn(new TransferStopResolver.SeqResolution(List.of(10, 40)));

        assertThat(enrichedBus(search(bus())).polyline()).isEqualTo(OTP_SHAPE);
        verify(polylinePort, never()).findSections(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("노선을 해석하지 못하면 바꾸지 않는다")
    void keepsShapeWhenRouteUnresolved() {
        when(resolver.resolveRoute("BL370", "370")).thenReturn(Optional.empty());

        assertThat(enrichedBus(search(bus())).polyline()).isEqualTo(OTP_SHAPE);
    }

    @Test
    @DisplayName("조회가 실패해도 검색은 원래 형상으로 응답한다")
    void keepsShapeOnLookupFailure() {
        when(polylinePort.findSections("R1", 10, 12)).thenThrow(new IllegalStateException("db down"));

        assertThat(enrichedBus(search(bus())).polyline()).isEqualTo(OTP_SHAPE);
    }

    @Test
    @DisplayName("여러 경로에 같은 버스가 있어도 한 번만 조회한다")
    void looksUpOncePerSearch() {
        when(polylinePort.findSections("R1", 10, 12)).thenReturn(List.of(
                section(10, 12, QualityStatus.OK, point(127.0, 37.5), point(127.02, 37.5))));
        PathResult path = new PathResult(1, null, List.of(bus()), List.of());

        enricher.enrich(new RouteSearchResult(0, 0, 0, 0, 0, List.of(path, path)));

        verify(resolver, times(1)).resolveRoute("BL370", "370");
        verify(resolver, times(1)).resolveSeq("R1", "S100");
    }

    private SubPathResult bus() {
        return SubPathFixture.bus(20).names("정류장", "회사앞")
                .coords(127.0, 37.5, 127.02, 37.5)
                .busStops("100", "200")
                .lanes(SubPathFixture.busLane("370", "BL370"))
                .polyline(OTP_SHAPE.toArray(CoordPoint[]::new))
                .build();
    }

    private RouteSearchResult search(SubPathResult bus) {
        SubPathResult walk = SubPathFixture.walk(3).names("집", "정류장")
                .coords(126.99, 37.5, 127.0, 37.5).build();
        return new RouteSearchResult(0, 0, 0, 0, 0,
                List.of(new PathResult(1, null, List.of(walk, bus), List.of())));
    }

    private SubPathResult enrichedBus(RouteSearchResult result) {
        return enricher.enrich(result).paths().get(0).subPaths().get(1);
    }

    private BusRouteSegmentPolylineSection section(int from, int to, QualityStatus quality,
                                                  BusRoutePolylineData.Point... points) {
        return new BusRouteSegmentPolylineSection(from, to, from + "-" + to, quality, null,
                List.of(points), null);
    }

    private BusRoutePolylineData.Point point(double x, double y) {
        return new BusRoutePolylineData.Point(x, y);
    }
}
