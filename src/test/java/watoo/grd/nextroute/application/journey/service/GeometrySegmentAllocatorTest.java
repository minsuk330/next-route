package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeoPoint;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeometryQuality;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.SegmentGeometry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("좌표를 구간으로 쪼개고 소요시간을 거리 비율로 나눈다")
class GeometrySegmentAllocatorTest {

    private final GeometrySegmentAllocator allocator = new GeometrySegmentAllocator();

    /** 서울 근처에서 경도 0.001도는 약 88m, 위도 0.001도는 약 111m다. */
    private static final double BASE_X = 127.0;
    private static final double BASE_Y = 37.5;

    @Nested
    @DisplayName("좌표를 선분 단위로 쪼갤 때")
    class Allocate {

        @Test
        @DisplayName("좌표 n개는 구간 n-1개가 되고 순번이 0부터 붙는다")
        void splitsIntoSegments() {
            List<SegmentGeometry> segments = allocator.allocate(line(4), 300);

            assertThat(segments).hasSize(3);
            assertThat(segments).extracting(SegmentGeometry::geometrySegmentIndex)
                    .containsExactly(0, 1, 2);
            assertThat(segments).allSatisfy(segment ->
                    assertThat(segment.points()).hasSize(2));
        }

        @Test
        @DisplayName("배분한 초의 합이 leg 소요시간과 정확히 같다")
        void preservesTotalSeconds() {
            // 나누어떨어지지 않는 값이라야 반올림 오차가 드러난다.
            List<SegmentGeometry> segments = allocator.allocate(line(7), 1000);

            assertThat(segments).hasSize(6);
            assertThat(segments.stream()
                    .mapToInt(SegmentGeometry::plannedDurationSeconds)
                    .sum()).isEqualTo(1000);
        }

        @Test
        @DisplayName("긴 구간이 더 많은 시간을 받는다")
        void allocatesByDistance() {
            List<GeoPoint> points = List.of(
                    new GeoPoint(BASE_X, BASE_Y),
                    new GeoPoint(BASE_X + 0.001, BASE_Y),   // 약 88m
                    new GeoPoint(BASE_X + 0.005, BASE_Y));  // 약 353m

            List<SegmentGeometry> segments = allocator.allocate(points, 600);

            assertThat(segments.get(0).plannedDurationSeconds())
                    .isLessThan(segments.get(1).plannedDurationSeconds());
            assertThat(segments.get(0).distanceMeters())
                    .isLessThan(segments.get(1).distanceMeters());
        }

        @Test
        @DisplayName("소요시간을 모르면 배분 시간을 비워 둔다")
        void leavesSecondsNullWhenUnknown() {
            List<SegmentGeometry> segments = allocator.allocate(line(3), null);

            assertThat(segments).hasSize(2);
            assertThat(segments).allSatisfy(segment ->
                    assertThat(segment.plannedDurationSeconds()).isNull());
        }

        @Test
        @DisplayName("좌표가 같은 지점만 반복되면 균등 분배한다")
        void splitsEvenlyWhenNoDistance() {
            List<GeoPoint> points = List.of(
                    new GeoPoint(BASE_X, BASE_Y),
                    new GeoPoint(BASE_X, BASE_Y),
                    new GeoPoint(BASE_X, BASE_Y));

            List<SegmentGeometry> segments = allocator.allocate(points, 100);

            assertThat(segments).hasSize(2);
            assertThat(segments.stream()
                    .mapToInt(SegmentGeometry::plannedDurationSeconds)
                    .sum()).isEqualTo(100);
        }

        @Test
        @DisplayName("좌표가 2개 미만이면 구간을 만들지 않는다")
        void needsTwoPoints() {
            assertThat(allocator.allocate(List.of(new GeoPoint(BASE_X, BASE_Y)), 60)).isEmpty();
            assertThat(allocator.allocate(List.of(), 60)).isEmpty();
            assertThat(allocator.allocate(null, 60)).isEmpty();
        }
    }

    @Nested
    @DisplayName("이미 나뉜 구간에 거리·시간만 채울 때")
    class Distribute {

        @Test
        @DisplayName("좌표가 없는 구간도 목록에서 빼지 않고 0m로 남긴다")
        void keepsEmptySegments() {
            List<SegmentGeometry> given = List.of(
                    section("a", List.of(new GeoPoint(BASE_X, BASE_Y),
                            new GeoPoint(BASE_X + 0.001, BASE_Y))),
                    section("b", List.of()),
                    section("c", List.of(new GeoPoint(BASE_X + 0.002, BASE_Y),
                            new GeoPoint(BASE_X + 0.003, BASE_Y))));

            List<SegmentGeometry> filled = allocator.distribute(given, 300);

            assertThat(filled).hasSize(3);
            assertThat(filled).extracting(SegmentGeometry::sectionId).containsExactly("a", "b", "c");
            assertThat(filled.get(1).distanceMeters()).isZero();
            assertThat(filled.get(1).plannedDurationSeconds()).isZero();
        }

        @Test
        @DisplayName("구간 순번을 0부터 다시 붙이고 sectionId·품질은 그대로 둔다")
        void reindexesAndKeepsQuality() {
            List<SegmentGeometry> given = List.of(
                    section("a", List.of(new GeoPoint(BASE_X, BASE_Y),
                            new GeoPoint(BASE_X + 0.001, BASE_Y))),
                    section("b", List.of(new GeoPoint(BASE_X + 0.001, BASE_Y),
                            new GeoPoint(BASE_X + 0.002, BASE_Y))));

            List<SegmentGeometry> filled = allocator.distribute(given, 120);

            assertThat(filled).extracting(SegmentGeometry::geometrySegmentIndex)
                    .containsExactly(0, 1);
            assertThat(filled).allSatisfy(segment ->
                    assertThat(segment.quality()).isEqualTo(GeometryQuality.OK));
        }

        @Test
        @DisplayName("배분한 초의 합이 leg 소요시간과 같다")
        void preservesTotalSeconds() {
            List<SegmentGeometry> given = List.of(
                    section("a", List.of(new GeoPoint(BASE_X, BASE_Y),
                            new GeoPoint(BASE_X + 0.0013, BASE_Y))),
                    section("b", List.of(new GeoPoint(BASE_X + 0.0013, BASE_Y),
                            new GeoPoint(BASE_X + 0.0071, BASE_Y))),
                    section("c", List.of(new GeoPoint(BASE_X + 0.0071, BASE_Y),
                            new GeoPoint(BASE_X + 0.0093, BASE_Y))));

            List<SegmentGeometry> filled = allocator.distribute(given, 997);

            assertThat(filled.stream()
                    .mapToInt(SegmentGeometry::plannedDurationSeconds)
                    .sum()).isEqualTo(997);
        }
    }

    private List<GeoPoint> line(int count) {
        List<GeoPoint> points = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(new GeoPoint(BASE_X + i * 0.001, BASE_Y));
        }
        return points;
    }

    private SegmentGeometry section(String sectionId, List<GeoPoint> points) {
        return new SegmentGeometry(0, sectionId, null, null, 0.0, null,
                GeometryQuality.OK, null, points);
    }
}
