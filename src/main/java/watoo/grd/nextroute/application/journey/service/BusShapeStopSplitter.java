package watoo.grd.nextroute.application.journey.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeoPoint;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeometryQuality;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.SegmentGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.StopGeometry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 버스 단계의 선(검색 결과의 shapes.txt 형상)을 정류장 위치에서 잘라 정류장 구간으로 나눈다.
 *
 * <p>앱의 진행 판정은 구간의 {@code fromSeq}·{@code toSeq} 로 "몇 번째 정류장까지 왔나"를 센다. 그래서
 * 선 원천을 shapes.txt 로 바꿔도 구간 단위 계약은 그대로 지켜야 한다.
 *
 * <p>정류장마다 선 위의 가장 가까운 지점을 찾되 <b>정류장 순서대로 앞으로만</b> 간다. 순환 노선처럼 선이
 * 같은 길을 두 번 지나면 가장 가까운 점이 엉뚱한 바퀴에 걸리기 쉬워서, 순서를 지키는 배치 중 거리 합이
 * 가장 작은 것을 고른다(DP). 첫 정류장은 선의 시작, 마지막 정류장은 선의 끝에 붙인다 — 미리보기와 같은
 * 선 전체를 빠짐없이 구간에 담기 위해서다.
 *
 * <p>좌표가 없는 정류장은 자르는 데 쓰지 않는다. 그 정류장은 앞뒤 구간 하나에 묻힌다.
 * 저장소를 보지 않고 아무것도 저장하지 않는다.
 */
@Component
public class BusShapeStopSplitter {

    /** 정류장이 선에서 이보다 멀면 그 정류장에 닿는 구간을 REVIEW 로 둔다. 진행 확정의 단독 근거로 쓰지 말라는 뜻이다. */
    static final double FAR_FROM_SHAPE_METERS = 150.0;
    static final String FAR_FROM_SHAPE = "STOP_FAR_FROM_SHAPE";
    static final String NO_SOURCE_GEOMETRY = "NO_SOURCE_GEOMETRY";

    private static final double METERS_PER_DEGREE_LAT = 110_540.0;
    private static final double METERS_PER_DEGREE_LON_AT_EQUATOR = 111_320.0;

    /**
     * 선을 정류장 구간으로 자른다. 거리·배분 시간은 채우지 않는다 — {@link GeometrySegmentAllocator#distribute} 몫이다.
     *
     * @param shape 승차~하차 선. 2점 미만이면 빈 목록
     * @param stops 승차~하차 정류장. 좌표 있는 정류장이 2개 미만이면 빈 목록
     */
    public List<SegmentGeometry> split(List<GeoPoint> shape, List<StopGeometry> stops) {
        List<StopGeometry> anchors = anchors(stops);
        if (shape == null || shape.size() < 2 || anchors.size() < 2) {
            return List.of();
        }
        List<Cut> cuts = place(shape, anchors);
        List<SegmentGeometry> segments = new ArrayList<>(anchors.size() - 1);
        for (int k = 0; k + 1 < cuts.size(); k++) {
            Cut from = cuts.get(k);
            Cut to = cuts.get(k + 1);
            boolean far = from.distanceMeters > FAR_FROM_SHAPE_METERS || to.distanceMeters > FAR_FROM_SHAPE_METERS;
            segments.add(new SegmentGeometry(
                    0, null, anchors.get(k).seq(), anchors.get(k + 1).seq(), 0.0, null,
                    far ? GeometryQuality.REVIEW : GeometryQuality.OK,
                    far ? FAR_FROM_SHAPE : null,
                    slice(shape, from, to)));
        }
        return segments;
    }

    /**
     * 선이 없을 때 정류장끼리 직선으로 잇는다. 미리보기도 선이 없으면 정류장 직선으로 그리므로 두 지도가 같다.
     * 실측이 아니므로 {@code APPROXIMATE} 다.
     */
    public List<SegmentGeometry> straight(List<StopGeometry> stops) {
        List<StopGeometry> anchors = anchors(stops);
        if (anchors.size() < 2) {
            return List.of();
        }
        List<SegmentGeometry> segments = new ArrayList<>(anchors.size() - 1);
        for (int k = 0; k + 1 < anchors.size(); k++) {
            StopGeometry from = anchors.get(k);
            StopGeometry to = anchors.get(k + 1);
            segments.add(new SegmentGeometry(
                    0, null, from.seq(), to.seq(), 0.0, null,
                    GeometryQuality.APPROXIMATE, NO_SOURCE_GEOMETRY,
                    List.of(new GeoPoint(from.x(), from.y()), new GeoPoint(to.x(), to.y()))));
        }
        return segments;
    }

    private List<StopGeometry> anchors(List<StopGeometry> stops) {
        if (stops == null) {
            return List.of();
        }
        return stops.stream()
                .filter(stop -> stop.x() != null && stop.y() != null)
                .sorted(Comparator.comparingInt(StopGeometry::seq))
                .toList();
    }

    /**
     * 정류장마다 선 위 자를 지점을 고른다. 첫·끝 정류장은 선의 양 끝에 고정하고, 사이 정류장은 순서를
     * 지키는 배치 중 거리 합이 최소인 것을 고른다.
     */
    private List<Cut> place(List<GeoPoint> shape, List<StopGeometry> anchors) {
        int edges = shape.size() - 1;
        int last = anchors.size() - 1;
        Cut start = new Cut(0, 0.0, shape.get(0), distance(anchors.get(0), shape.get(0)));
        Cut end = new Cut(edges - 1, 1.0, shape.get(edges), distance(anchors.get(last), shape.get(edges)));
        if (last == 1) {
            return List.of(start, end);
        }

        int middle = last - 1;
        Cut[][] best = new Cut[middle][edges];
        double[][] cost = new double[middle][edges];
        int[][] from = new int[middle][edges];
        for (int k = 0; k < middle; k++) {
            StopGeometry stop = anchors.get(k + 1);
            // 앞 정류장이 더 앞 선분에 있으면 어디서 와도 된다. 같은 선분이면 앞 정류장보다 뒤(t 이상)일 때만.
            double earlierMin = Double.POSITIVE_INFINITY;
            int earlierArg = -1;
            for (int i = 0; i < edges; i++) {
                Cut cut = project(stop, shape, i);
                best[k][i] = cut;
                if (k == 0) {
                    cost[k][i] = cut.distanceMeters;
                } else {
                    double previous = earlierMin;
                    int previousArg = earlierArg;
                    if (best[k - 1][i].t <= cut.t && cost[k - 1][i] < previous) {
                        previous = cost[k - 1][i];
                        previousArg = i;
                    }
                    cost[k][i] = cut.distanceMeters + previous;
                    from[k][i] = previousArg;
                    if (cost[k - 1][i] < earlierMin) {
                        earlierMin = cost[k - 1][i];
                        earlierArg = i;
                    }
                }
            }
        }

        int arg = 0;
        for (int i = 1; i < edges; i++) {
            if (cost[middle - 1][i] < cost[middle - 1][arg]) {
                arg = i;
            }
        }
        Cut[] chosen = new Cut[middle];
        for (int k = middle - 1; k >= 0; k--) {
            chosen[k] = best[k][arg];
            arg = from[k][arg];
        }

        List<Cut> cuts = new ArrayList<>(anchors.size());
        cuts.add(start);
        Cut previous = start;
        for (Cut cut : chosen) {
            // 같은 선분 위에서 뒤 정류장이 앞 정류장보다 뒤로 가지 않게 한다.
            Cut ordered = cut.edge == previous.edge && cut.t < previous.t
                    ? new Cut(previous.edge, previous.t, previous.point, cut.distanceMeters) : cut;
            cuts.add(ordered);
            previous = ordered;
        }
        cuts.add(end);
        return cuts;
    }

    /** 두 자를 지점 사이의 선. 시작·끝은 자른 지점이고 그 사이는 원래 꼭짓점이다. */
    private List<GeoPoint> slice(List<GeoPoint> shape, Cut from, Cut to) {
        List<GeoPoint> points = new ArrayList<>();
        add(points, from.point);
        for (int v = from.edge + 1; v <= to.edge; v++) {
            add(points, shape.get(v));
        }
        add(points, to.point);
        if (points.size() < 2) {
            // 두 정류장이 선 위 같은 지점에 놓였다. 길이 0 구간이지만 구간 수는 지켜야 한다.
            points.add(points.get(0));
        }
        return points;
    }

    private void add(List<GeoPoint> points, GeoPoint point) {
        if (points.isEmpty() || !same(points.get(points.size() - 1), point)) {
            points.add(point);
        }
    }

    private boolean same(GeoPoint a, GeoPoint b) {
        return Double.compare(a.x(), b.x()) == 0 && Double.compare(a.y(), b.y()) == 0;
    }

    /** 정류장을 선분 {@code edge} 위에 수선의 발로 내린다. 정류장 위도 기준 평면 근사로 충분하다(수백 m 규모). */
    private Cut project(StopGeometry stop, List<GeoPoint> shape, int edge) {
        GeoPoint a = shape.get(edge);
        GeoPoint b = shape.get(edge + 1);
        double lonScale = METERS_PER_DEGREE_LON_AT_EQUATOR * Math.cos(Math.toRadians(stop.y()));
        double ax = (a.x() - stop.x()) * lonScale;
        double ay = (a.y() - stop.y()) * METERS_PER_DEGREE_LAT;
        double bx = (b.x() - stop.x()) * lonScale;
        double by = (b.y() - stop.y()) * METERS_PER_DEGREE_LAT;
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0 ? 0.0 : Math.max(0.0, Math.min(1.0, -(ax * dx + ay * dy) / lengthSquared));
        double px = ax + t * dx;
        double py = ay + t * dy;
        GeoPoint point = t == 0.0 ? a : t == 1.0 ? b
                : new GeoPoint(a.x() + t * (b.x() - a.x()), a.y() + t * (b.y() - a.y()));
        return new Cut(edge, t, point, Math.hypot(px, py));
    }

    private double distance(StopGeometry stop, GeoPoint point) {
        double lonScale = METERS_PER_DEGREE_LON_AT_EQUATOR * Math.cos(Math.toRadians(stop.y()));
        return Math.hypot((point.x() - stop.x()) * lonScale, (point.y() - stop.y()) * METERS_PER_DEGREE_LAT);
    }

    /** 선 위 자를 지점. {@code edge} 번째 선분의 {@code t} 비율 위치다. */
    private record Cut(int edge, double t, GeoPoint point, double distanceMeters) {
    }
}
