package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeoPoint;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeometryQuality;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.SegmentGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.StopGeometry;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("버스 선을 정류장 구간으로 자른다")
class BusShapeStopSplitterTest {

    private final BusShapeStopSplitter splitter = new BusShapeStopSplitter();

    @Test
    @DisplayName("정류장마다 선 위 가장 가까운 지점에서 자르고, 잘린 구간을 이으면 원래 선이 된다")
    void cutsAtNearestPoints() {
        List<GeoPoint> shape = List.of(p(127.000, 37.5), p(127.010, 37.5), p(127.020, 37.5));
        List<StopGeometry> stops = List.of(
                stop(1, 127.000, 37.5), stop(2, 127.005, 37.5001), stop(3, 127.020, 37.5));

        List<SegmentGeometry> segments = splitter.split(shape, stops);

        assertThat(segments).extracting(SegmentGeometry::fromSeq).containsExactly(1, 2);
        assertThat(segments).extracting(SegmentGeometry::toSeq).containsExactly(2, 3);
        assertThat(segments.get(0).points().get(segments.get(0).points().size() - 1).x())
                .isCloseTo(127.005, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(joined(segments)).containsSubsequence(shape);
        assertThat(segments).extracting(SegmentGeometry::quality).containsOnly(GeometryQuality.OK);
    }

    @Test
    @DisplayName("순환 노선: 같은 길을 두 번 지나도 정류장 순서대로 앞쪽 바퀴에 붙인다")
    void keepsStopOrderOnLoops() {
        // 동쪽으로 갔다가 같은 길로 돌아온다. 중간 정류장 2(가는 길)와 4(오는 길)는 같은 자리다.
        List<GeoPoint> shape = List.of(
                p(127.000, 37.5), p(127.010, 37.5), p(127.020, 37.5),
                p(127.010, 37.5), p(127.000, 37.5));
        List<StopGeometry> stops = List.of(
                stop(1, 127.000, 37.5), stop(2, 127.010, 37.5), stop(3, 127.020, 37.5),
                stop(4, 127.010, 37.5), stop(5, 127.000, 37.5));

        List<SegmentGeometry> segments = splitter.split(shape, stops);

        assertThat(segments).hasSize(4);
        // 각 구간이 한 칸씩만 이동한다(정류장 2 가 오는 길에, 4 가 가는 길에 붙으면 길이가 깨진다).
        assertThat(segments).allSatisfy(segment -> {
            List<GeoPoint> points = segment.points();
            assertThat(Math.abs(points.get(points.size() - 1).x() - points.get(0).x()))
                    .isCloseTo(0.010, org.assertj.core.data.Offset.offset(1e-9));
        });
    }

    @Test
    @DisplayName("좌표 없는 정류장은 자르지 않고 구간에 묻는다")
    void skipsStopsWithoutCoordinates() {
        List<GeoPoint> shape = List.of(p(127.000, 37.5), p(127.020, 37.5));
        List<StopGeometry> stops = List.of(
                stop(1, 127.000, 37.5), new StopGeometry(2, "S2", "좌표 없음", null, null),
                stop(3, 127.020, 37.5));

        List<SegmentGeometry> segments = splitter.split(shape, stops);

        assertThat(segments).singleElement().satisfies(segment -> {
            assertThat(segment.fromSeq()).isEqualTo(1);
            assertThat(segment.toSeq()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("선에서 멀리 떨어진 정류장에 닿는 구간은 REVIEW")
    void marksFarStopsForReview() {
        List<GeoPoint> shape = List.of(p(127.000, 37.5), p(127.010, 37.5), p(127.020, 37.5));
        List<StopGeometry> stops = List.of(
                stop(1, 127.000, 37.5), stop(2, 127.010, 37.505), stop(3, 127.020, 37.5));

        List<SegmentGeometry> segments = splitter.split(shape, stops);

        assertThat(segments).extracting(SegmentGeometry::quality)
                .containsExactly(GeometryQuality.REVIEW, GeometryQuality.REVIEW);
        assertThat(segments).extracting(SegmentGeometry::qualityReason)
                .containsOnly(BusShapeStopSplitter.FAR_FROM_SHAPE);
    }

    @Test
    @DisplayName("두 정류장이 선 위 같은 지점이면 길이 0 구간이라도 구간 수를 지킨다")
    void keepsZeroLengthSections() {
        List<GeoPoint> shape = List.of(p(127.000, 37.5), p(127.020, 37.5));
        List<StopGeometry> stops = List.of(
                stop(1, 127.000, 37.5), stop(2, 127.010, 37.5), stop(3, 127.010, 37.5),
                stop(4, 127.020, 37.5));

        List<SegmentGeometry> segments = splitter.split(shape, stops);

        assertThat(segments).hasSize(3);
        assertThat(segments).allSatisfy(segment -> assertThat(segment.points()).hasSizeGreaterThanOrEqualTo(2));
    }

    @Test
    @DisplayName("선이 2점 미만이거나 좌표 있는 정류장이 2개 미만이면 자르지 않는다")
    void needsShapeAndTwoStops() {
        List<StopGeometry> stops = List.of(stop(1, 127.0, 37.5), stop(2, 127.02, 37.5));

        assertThat(splitter.split(List.of(p(127.0, 37.5)), stops)).isEmpty();
        assertThat(splitter.split(List.of(p(127.0, 37.5), p(127.02, 37.5)),
                List.of(stop(1, 127.0, 37.5)))).isEmpty();
    }

    @Test
    @DisplayName("선이 없을 때는 정류장 직선 APPROXIMATE")
    void straightLinesBetweenStops() {
        List<SegmentGeometry> segments = splitter.straight(List.of(
                stop(1, 127.0, 37.5), stop(2, 127.01, 37.5), stop(3, 127.02, 37.5)));

        assertThat(segments).hasSize(2);
        assertThat(segments).extracting(SegmentGeometry::quality).containsOnly(GeometryQuality.APPROXIMATE);
        assertThat(segments.get(1).points()).containsExactly(p(127.01, 37.5), p(127.02, 37.5));
    }

    private List<GeoPoint> joined(List<SegmentGeometry> segments) {
        List<GeoPoint> all = new ArrayList<>();
        for (SegmentGeometry segment : segments) {
            for (GeoPoint point : segment.points()) {
                if (all.isEmpty() || !all.get(all.size() - 1).equals(point)) {
                    all.add(point);
                }
            }
        }
        return all;
    }

    private GeoPoint p(double x, double y) {
        return new GeoPoint(x, y);
    }

    private StopGeometry stop(int seq, double x, double y) {
        return new StopGeometry(seq, "S" + seq, "정류장" + seq, x, y);
    }
}
