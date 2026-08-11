package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeoPoint;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeometryQuality;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.ManeuverGeometry;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.dto.WalkStep;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 도보 안내점을 폴리라인 위 위치로 바꾼다.
 *
 * <p>왜 투영이 주된 방법이 아닌가 — TMAP 응답에서 안내점 좌표는 <b>폴리라인의 정점 그 자체</b>다.
 * 안내점을 만드는 Point feature 의 좌표를 폴리라인에도 그대로 넣기 때문이다
 * ({@code TmapPedestrianAdapter#toWalkSegment}). 그래서 "가장 가까운 선분 찾기"가 아니라
 * "앞에서부터 같은 정점 찾기"가 정답이고, 이러면 경로가 왕복·교차할 때 뒤쪽 선분에 잘못 붙는 일이
 * 아예 생기지 않는다. 투영은 좌표가 정점과 맞지 않을 때의 폴백이다.
 *
 * <p>또 하나 — 안내점은 <b>앞으로만 간다.</b> 이미 지난 지점보다 뒤로 가는 anchor 는 버린다. 앱은
 * 이 목록의 누적거리가 증가한다는 것을 믿고 "다음 안내까지 남은 거리"를 계산한다.
 *
 * <p>붙이지 못한 안내점은 목록에서 빠진다. 응답 전체를 실패시키지 않는다 — 지도와 진행 판정은
 * 안내점 없이도 동작하고, 틀린 방향을 자신 있게 말하는 것이 가장 나쁘다.
 *
 * <p>저장소를 보지 않고 아무것도 저장하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalkManeuverAnchorer {

    /** 좌표 직렬화 왕복 오차만 흡수하는 폭. 약 1cm. */
    private static final double VERTEX_EPSILON_DEGREES = 1e-7;

    /** 이보다 멀리 떨어진 안내점은 이 경로의 것이 아니라고 본다. */
    private static final double MAX_OFFSET_METERS = 30.0;

    private static final double METERS_PER_DEGREE_LAT = 110_540.0;
    private static final double METERS_PER_DEGREE_LON_AT_EQUATOR = 111_320.0;

    private final GeometrySegmentAllocator allocator;
    private final WalkManeuverTypeMapper typeMapper;

    /**
     * @param steps       얼려 둔 검색 결과의 TMAP 안내점. null 일 수 있다
     * @param polyline    도보 폴리라인. {@code GeometrySegmentAllocator#allocate} 에 들어가는 그 목록
     *                    이어야 한다 — 구간 순번의 기준이 같아야 한다
     * @param legQuality  단계 좌표 품질. 직선 대체거나 좌표가 없으면 안내점을 만들지 않는다
     */
    public List<ManeuverGeometry> anchor(List<WalkStep> steps, List<CoordPoint> polyline,
                                        GeometryQuality legQuality) {
        if (steps == null || steps.isEmpty() || polyline == null || polyline.size() < 2) {
            return List.of();
        }
        // 직선 대체 좌표 위의 "좌회전"은 지도에 없는 길을 가리킨다.
        if (legQuality == GeometryQuality.APPROXIMATE || legQuality == GeometryQuality.FAILED) {
            return List.of();
        }

        int segmentCount = polyline.size() - 1;
        double[] prefix = prefixDistances(polyline);
        double perDegreeLon = metersPerDegreeLon(polyline.get(0).y());

        List<WalkStep> ordered = steps.stream()
                .sorted(Comparator.comparingInt(WalkStep::index))
                .toList();

        List<ManeuverGeometry> result = new ArrayList<>(ordered.size());
        int vertexCursor = 0;
        double lastDistance = -1.0;
        int dropped = 0;

        for (WalkStep step : ordered) {
            if (!usableCoordinate(step)) {
                dropped++;
                continue;
            }
            Anchor anchor = findAnchor(step, polyline, vertexCursor, segmentCount, prefix,
                    perDegreeLon);
            if (anchor == null) {
                dropped++;
                continue;
            }
            if (anchor.distanceMeters() < lastDistance) {
                // 앞선 안내점보다 뒤로 가는 위치는 잘못 붙은 것이다.
                dropped++;
                continue;
            }
            lastDistance = anchor.distanceMeters();
            vertexCursor = anchor.vertexCursor();
            result.add(new ManeuverGeometry(
                    result.size(),
                    typeMapper.map(step.pointType(), step.turnType()),
                    step.description(),
                    anchor.segmentIndex(),
                    anchor.segmentProgress(),
                    round(anchor.distanceMeters()),
                    step.x(),
                    step.y()));
        }

        if (dropped > 0) {
            // 좌표는 남기지 않는다. 정밀 좌표를 서버에 남기지 않는다는 결정과 같은 결이다.
            log.debug("도보 안내점 anchor 실패 — 제외 {}/{}", dropped, ordered.size());
        }
        return List.copyOf(result);
    }

    /**
     * 정점 정확일치 → 실패 시 투영.
     *
     * <p>둘 다 {@code vertexCursor} 이후만 본다. 지난 경로로 되돌아가지 않기 위해서다.
     */
    private Anchor findAnchor(WalkStep step, List<CoordPoint> polyline, int vertexCursor,
                              int segmentCount, double[] prefix, double perDegreeLon) {
        for (int i = vertexCursor; i < polyline.size(); i++) {
            CoordPoint vertex = polyline.get(i);
            if (Math.abs(vertex.x() - step.x()) <= VERTEX_EPSILON_DEGREES
                    && Math.abs(vertex.y() - step.y()) <= VERTEX_EPSILON_DEGREES) {
                // 마지막 정점은 시작하는 구간이 없다. 마지막 구간의 끝으로 표현한다.
                if (i == polyline.size() - 1) {
                    return new Anchor(segmentCount - 1, 1.0, prefix[i], i);
                }
                return new Anchor(i, 0.0, prefix[i], i);
            }
        }
        return project(step, polyline, vertexCursor, prefix, perDegreeLon);
    }

    /** 남은 선분 중 수직거리가 가장 짧은 곳에 붙인다. 허용 폭을 넘으면 포기한다. */
    private Anchor project(WalkStep step, List<CoordPoint> polyline, int vertexCursor,
                           double[] prefix, double perDegreeLon) {
        int bestSegment = -1;
        double bestOffset = Double.MAX_VALUE;
        double bestProgress = 0.0;

        for (int i = vertexCursor; i < polyline.size() - 1; i++) {
            CoordPoint from = polyline.get(i);
            CoordPoint to = polyline.get(i + 1);
            double ax = (to.x() - from.x()) * perDegreeLon;
            double ay = (to.y() - from.y()) * METERS_PER_DEGREE_LAT;
            double px = (step.x() - from.x()) * perDegreeLon;
            double py = (step.y() - from.y()) * METERS_PER_DEGREE_LAT;

            double lengthSquared = ax * ax + ay * ay;
            double t = lengthSquared == 0.0
                    ? 0.0
                    : clamp((px * ax + py * ay) / lengthSquared);
            double offsetX = px - ax * t;
            double offsetY = py - ay * t;
            double offset = Math.sqrt(offsetX * offsetX + offsetY * offsetY);

            if (offset < bestOffset) {
                bestOffset = offset;
                bestSegment = i;
                bestProgress = t;
            }
        }

        if (bestSegment < 0 || bestOffset > MAX_OFFSET_METERS) {
            return null;
        }
        double segmentLength = prefix[bestSegment + 1] - prefix[bestSegment];
        return new Anchor(bestSegment, bestProgress,
                prefix[bestSegment] + segmentLength * bestProgress, bestSegment);
    }

    /** 정점까지의 누적거리. 반올림하지 않는다 — 마지막에 한 번만 반올림한다. */
    private double[] prefixDistances(List<CoordPoint> polyline) {
        double[] prefix = new double[polyline.size()];
        for (int i = 0; i < polyline.size() - 1; i++) {
            prefix[i + 1] = prefix[i] + allocator.distanceMeters(
                    toGeoPoint(polyline.get(i)), toGeoPoint(polyline.get(i + 1)));
        }
        return prefix;
    }

    private boolean usableCoordinate(WalkStep step) {
        if (!Double.isFinite(step.x()) || !Double.isFinite(step.y())) {
            return false;
        }
        // (0,0) 은 TMAP 이 좌표를 못 준 자리다. 대서양 한가운데로 안내할 수 없다.
        return step.x() != 0.0 || step.y() != 0.0;
    }

    private double metersPerDegreeLon(double latitude) {
        return METERS_PER_DEGREE_LON_AT_EQUATOR * Math.cos(Math.toRadians(latitude));
    }

    private GeoPoint toGeoPoint(CoordPoint point) {
        return new GeoPoint(point.x(), point.y());
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double meters) {
        return Math.round(meters * 10.0) / 10.0;
    }

    /**
     * 붙인 결과.
     *
     * @param vertexCursor 다음 안내점 탐색을 시작할 정점 순번
     */
    private record Anchor(int segmentIndex, double segmentProgress, double distanceMeters,
                          int vertexCursor) {
    }
}
