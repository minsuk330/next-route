package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeometryQuality;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.ManeuverGeometry;
import watoo.grd.nextroute.application.journey.dto.ManeuverType;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.dto.WalkStep;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 안내점을 폴리라인 위에 붙이는 규칙.
 *
 * <p>좌표는 서울 시내 값을 쓴다. 위도 37.5 에서 경도 0.001 은 약 88m, 위도 0.001 은 약 111m 다.
 */
class WalkManeuverAnchorerTest {

    private final WalkManeuverAnchorer anchorer =
            new WalkManeuverAnchorer(new GeometrySegmentAllocator(), new WalkManeuverTypeMapper());

    private static final double LAT = 37.5000;

    /** 경도만 0.001 씩 동쪽으로 가는 직선 폴리라인. */
    private List<CoordPoint> eastwardLine(int vertexCount) {
        List<CoordPoint> points = new java.util.ArrayList<>(vertexCount);
        for (int i = 0; i < vertexCount; i++) {
            points.add(new CoordPoint(127.0000 + 0.001 * i, LAT));
        }
        return points;
    }

    private WalkStep step(int index, String pointType, Integer turnType, CoordPoint at) {
        return new WalkStep(index, pointType, turnType, "안내 " + index, at.x(), at.y());
    }

    @Test
    @DisplayName("첫 정점의 안내점은 0번 구간 시작이고 누적거리 0")
    void anchorsAtFirstVertex() {
        List<CoordPoint> polyline = eastwardLine(4);
        List<WalkStep> steps = List.of(step(0, "SP", 200, polyline.get(0)));

        List<ManeuverGeometry> result = anchorer.anchor(steps, polyline, GeometryQuality.OK);

        assertThat(result).hasSize(1);
        ManeuverGeometry maneuver = result.get(0);
        assertThat(maneuver.maneuverIndex()).isZero();
        assertThat(maneuver.type()).isEqualTo(ManeuverType.DEPART);
        assertThat(maneuver.instruction()).isEqualTo("안내 0");
        assertThat(maneuver.geometrySegmentIndex()).isZero();
        assertThat(maneuver.segmentProgress()).isZero();
        assertThat(maneuver.distanceFromLegStartMeters()).isZero();
        assertThat(maneuver.x()).isEqualTo(polyline.get(0).x());
        assertThat(maneuver.y()).isEqualTo(polyline.get(0).y());
    }

    @Test
    @DisplayName("중간 정점의 안내점은 그 정점에서 시작하는 구간의 시작이다")
    void anchorsAtMiddleVertex() {
        List<CoordPoint> polyline = eastwardLine(5);
        List<WalkStep> steps = List.of(step(1, "GP", 12, polyline.get(2)));

        List<ManeuverGeometry> result = anchorer.anchor(steps, polyline, GeometryQuality.OK);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).geometrySegmentIndex()).isEqualTo(2);
        assertThat(result.get(0).segmentProgress()).isZero();
        assertThat(result.get(0).type()).isEqualTo(ManeuverType.LEFT);
        // 88m 짜리 선분 2개를 지난 지점
        assertThat(result.get(0).distanceFromLegStartMeters()).isCloseTo(176.6, org.assertj.core.data.Offset.offset(2.0));
    }

    @Test
    @DisplayName("마지막 정점의 안내점은 마지막 구간의 끝이다")
    void anchorsAtLastVertex() {
        List<CoordPoint> polyline = eastwardLine(4);
        List<WalkStep> steps = List.of(step(3, "EP", 201, polyline.get(3)));

        List<ManeuverGeometry> result = anchorer.anchor(steps, polyline, GeometryQuality.OK);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).geometrySegmentIndex()).isEqualTo(2); // 구간 수 3개 → 마지막 순번 2
        assertThat(result.get(0).segmentProgress()).isEqualTo(1.0);
        assertThat(result.get(0).type()).isEqualTo(ManeuverType.ARRIVE);
        assertThat(result.get(0).distanceFromLegStartMeters()).isCloseTo(264.9, org.assertj.core.data.Offset.offset(3.0));
    }

    @Test
    @DisplayName("정점과 일치하지 않으면 구간 안 비율로 붙는다")
    void projectsWhenNotOnVertex() {
        List<CoordPoint> polyline = eastwardLine(3);
        // 0번 구간의 절반 지점, 경로에서 살짝(약 2m) 벗어난 좌표
        CoordPoint offRoute = new CoordPoint(127.0005, LAT + 0.00002);
        List<WalkStep> steps = List.of(step(1, "GP", 13, offRoute));

        List<ManeuverGeometry> result = anchorer.anchor(steps, polyline, GeometryQuality.OK);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).geometrySegmentIndex()).isZero();
        assertThat(result.get(0).segmentProgress()).isBetween(0.4, 0.6);
        assertThat(result.get(0).distanceFromLegStartMeters()).isCloseTo(44.2, org.assertj.core.data.Offset.offset(3.0));
        // 지도 핀은 원본 좌표 그대로다
        assertThat(result.get(0).x()).isEqualTo(offRoute.x());
        assertThat(result.get(0).y()).isEqualTo(offRoute.y());
    }

    @Test
    @DisplayName("안내점이 여럿이면 순번은 0부터 연속이고 누적거리는 증가한다")
    void indexesAreContinuousAndDistanceIncreases() {
        List<CoordPoint> polyline = eastwardLine(6);
        List<WalkStep> steps = List.of(
                step(0, "SP", 200, polyline.get(0)),
                step(1, "GP", 12, polyline.get(2)),
                step(2, "GP", 13, polyline.get(4)),
                step(3, "EP", 201, polyline.get(5)));

        List<ManeuverGeometry> result = anchorer.anchor(steps, polyline, GeometryQuality.OK);

        assertThat(result).hasSize(4);
        assertThat(result).extracting(ManeuverGeometry::maneuverIndex).containsExactly(0, 1, 2, 3);
        assertThat(result).extracting(ManeuverGeometry::distanceFromLegStartMeters)
                .isSorted();
        assertThat(result).extracting(ManeuverGeometry::geometrySegmentIndex)
                .containsExactly(0, 2, 4, 4);
    }

    @Test
    @DisplayName("같은 구간에 안내점이 둘이면 비율이 커지는 순서로 남는다")
    void twoManeuversInOneSegment() {
        List<CoordPoint> polyline = eastwardLine(2);
        List<WalkStep> steps = List.of(
                step(1, "GP", 11, new CoordPoint(127.00025, LAT)),
                step(2, "GP", 12, new CoordPoint(127.00075, LAT)));

        List<ManeuverGeometry> result = anchorer.anchor(steps, polyline, GeometryQuality.OK);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).geometrySegmentIndex()).isZero();
        assertThat(result.get(1).geometrySegmentIndex()).isZero();
        assertThat(result.get(0).segmentProgress()).isLessThan(result.get(1).segmentProgress());
    }

    @Test
    @DisplayName("경로가 왕복해 같은 좌표가 다시 나와도 뒤로 점프하지 않는다")
    void neverJumpsBackwardOnLoop() {
        // 동쪽으로 갔다가 되돌아오는 경로. 127.001 좌표가 두 번 나온다.
        List<CoordPoint> polyline = List.of(
                new CoordPoint(127.000, LAT),
                new CoordPoint(127.001, LAT),
                new CoordPoint(127.002, LAT),
                new CoordPoint(127.001, LAT),
                new CoordPoint(127.000, LAT));
        List<WalkStep> steps = List.of(
                step(0, "SP", 200, new CoordPoint(127.002, LAT)),   // 되돌기 전 꼭짓점
                step(1, "GP", 14, new CoordPoint(127.001, LAT)),    // 되돌아오는 쪽이어야 한다
                step(2, "EP", 201, new CoordPoint(127.000, LAT)));

        List<ManeuverGeometry> result = anchorer.anchor(steps, polyline, GeometryQuality.OK);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(ManeuverGeometry::geometrySegmentIndex)
                .containsExactly(2, 3, 3);
        assertThat(result).extracting(ManeuverGeometry::distanceFromLegStartMeters).isSorted();
    }

    @Test
    @DisplayName("경로에서 너무 멀리 떨어진 안내점만 빠지고 나머지는 남는다")
    void dropsFarManeuverButKeepsRest() {
        List<CoordPoint> polyline = eastwardLine(4);
        List<WalkStep> steps = List.of(
                step(0, "SP", 200, polyline.get(0)),
                step(1, "GP", 12, new CoordPoint(127.0015, LAT + 0.01)),  // 약 1.1km 이탈
                step(2, "EP", 201, polyline.get(3)));

        List<ManeuverGeometry> result = anchorer.anchor(steps, polyline, GeometryQuality.OK);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ManeuverGeometry::type)
                .containsExactly(ManeuverType.DEPART, ManeuverType.ARRIVE);
        assertThat(result).extracting(ManeuverGeometry::maneuverIndex).containsExactly(0, 1);
    }

    @Test
    @DisplayName("좌표가 0,0 이면 제외한다")
    void dropsZeroCoordinate() {
        List<CoordPoint> polyline = eastwardLine(3);
        List<WalkStep> steps = List.of(
                step(0, "GP", 11, new CoordPoint(0.0, 0.0)),
                step(1, "EP", 201, polyline.get(2)));

        List<ManeuverGeometry> result = anchorer.anchor(steps, polyline, GeometryQuality.OK);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo(ManeuverType.ARRIVE);
    }

    @Test
    @DisplayName("길이 0 선분이 섞여도 구간 순번은 구간 목록 범위를 벗어나지 않는다")
    void survivesZeroLengthSegments() {
        List<CoordPoint> polyline = List.of(
                new CoordPoint(127.000, LAT),
                new CoordPoint(127.000, LAT),   // 중복 좌표 → 길이 0 선분
                new CoordPoint(127.001, LAT),
                new CoordPoint(127.002, LAT));
        int segmentCount = new GeometrySegmentAllocator()
                .allocate(polyline.stream()
                        .map(p -> new watoo.grd.nextroute.application.journey.dto
                                .JourneyNavigationGeometry.GeoPoint(p.x(), p.y()))
                        .toList(), 300)
                .size();
        List<WalkStep> steps = List.of(
                step(0, "SP", 200, polyline.get(0)),
                step(1, "GP", 12, polyline.get(2)),
                step(2, "EP", 201, polyline.get(3)));

        List<ManeuverGeometry> result = anchorer.anchor(steps, polyline, GeometryQuality.OK);

        assertThat(result).hasSize(3);
        assertThat(result).allSatisfy(maneuver ->
                assertThat(maneuver.geometrySegmentIndex()).isBetween(0, segmentCount - 1));
        assertThat(result).extracting(ManeuverGeometry::distanceFromLegStartMeters).isSorted();
    }

    @Test
    @DisplayName("붙일 폴리라인이 없거나 품질이 낮으면 안내점을 주지 않는다")
    void emptyWhenNoUsableGeometry() {
        List<CoordPoint> polyline = eastwardLine(3);
        List<WalkStep> steps = List.of(step(0, "SP", 200, polyline.get(0)));

        assertThat(anchorer.anchor(steps, null, GeometryQuality.OK)).isEmpty();
        assertThat(anchorer.anchor(steps, List.of(), GeometryQuality.OK)).isEmpty();
        assertThat(anchorer.anchor(steps, polyline.subList(0, 1), GeometryQuality.OK)).isEmpty();
        assertThat(anchorer.anchor(null, polyline, GeometryQuality.OK)).isEmpty();
        assertThat(anchorer.anchor(List.of(), polyline, GeometryQuality.OK)).isEmpty();
        // 직선 대체·좌표 없음 위에 회전 안내를 얹으면 거짓 방향이 된다
        assertThat(anchorer.anchor(steps, polyline, GeometryQuality.APPROXIMATE)).isEmpty();
        assertThat(anchorer.anchor(steps, polyline, GeometryQuality.FAILED)).isEmpty();
    }

    @Test
    @DisplayName("검토 대상 품질은 안내점을 준다 — 지도 표시가 가능한 좌표다")
    void keepsManeuversForReviewQuality() {
        List<CoordPoint> polyline = eastwardLine(3);
        List<WalkStep> steps = List.of(step(0, "SP", 200, polyline.get(0)));

        assertThat(anchorer.anchor(steps, polyline, GeometryQuality.REVIEW)).hasSize(1);
    }

    @Test
    @DisplayName("원천 순번이 뒤섞여 와도 순번 순서로 처리한다")
    void sortsBySourceIndex() {
        List<CoordPoint> polyline = eastwardLine(4);
        List<WalkStep> steps = List.of(
                step(2, "EP", 201, polyline.get(3)),
                step(0, "SP", 200, polyline.get(0)),
                step(1, "GP", 12, polyline.get(1)));

        List<ManeuverGeometry> result = anchorer.anchor(steps, polyline, GeometryQuality.OK);

        assertThat(result).extracting(ManeuverGeometry::type)
                .containsExactly(ManeuverType.DEPART, ManeuverType.LEFT, ManeuverType.ARRIVE);
    }
}
