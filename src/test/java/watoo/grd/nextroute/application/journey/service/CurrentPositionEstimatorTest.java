package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeoPoint;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeometryQuality;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.SegmentGeometry;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("걷는 중 현재 위치 추정(#264 걷기 기점 A안)")
class CurrentPositionEstimatorTest {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");
    private static final Duration MAX_AGE = Duration.ofSeconds(180);
    /** 서쪽→동쪽으로 곧은 두 구간. 0번 구간은 127.00→127.01, 1번 구간은 127.01→127.02. */
    private static final LegGeometry GEOMETRY = new LegGeometry(0, JourneyLegType.WALK, null, 600, 2000,
            GeometryQuality.OK, List.of(),
            List.of(segment(0, 127.00, 127.01), segment(1, 127.01, 127.02)));

    private final CurrentPositionEstimator estimator =
            new CurrentPositionEstimator(new GeometrySegmentAllocator());

    @Test
    @DisplayName("진행 구간의 진행률만큼 거리 기준으로 간 지점이다")
    void interpolatesAlongReportedSegment() {
        GeoPoint point = estimator.estimate(walk(1, 0.5, NOW.minusSeconds(10)), GEOMETRY, NOW, MAX_AGE)
                .orElseThrow();

        assertThat(point.x()).isCloseTo(127.015, within(1e-6));
        assertThat(point.y()).isCloseTo(37.5, within(1e-9));
    }

    @Test
    @DisplayName("진행 보고가 180초보다 오래됐으면 모른다 — 낡은 위치로 찾으면 되돌아가라는 답이 나온다")
    void staleProgressIsUnknown() {
        assertThat(estimator.estimate(walk(0, 0.5, NOW.minusSeconds(181)), GEOMETRY, NOW, MAX_AGE)).isEmpty();
    }

    @Test
    @DisplayName("진행 보고 전이라도 단계 시작 직후면 시작점이다")
    void freshStepWithoutProgressStartsAtBeginning() {
        JourneyLeg leg = walk(null, null, null);
        ReflectionTestUtils.setField(leg, "activatedAt", NOW.minusSeconds(30));

        assertThat(estimator.estimate(leg, GEOMETRY, NOW, MAX_AGE)).contains(new GeoPoint(127.00, 37.5));
    }

    @Test
    @DisplayName("구간 순번이 geometry 밖이면 모른다")
    void segmentOutOfRangeIsUnknown() {
        assertThat(estimator.estimate(walk(5, 0.5, NOW), GEOMETRY, NOW, MAX_AGE)).isEmpty();
    }

    private static JourneyLeg walk(Integer segment, Double fraction, Instant observedAt) {
        JourneyLeg leg = JourneyLeg.builder().journeyId(1L).routeRevision(1).legIndex(0)
                .type(JourneyLegType.WALK).state(JourneyLegState.ACTIVE).plannedDurationSec(600).build();
        ReflectionTestUtils.setField(leg, "progressGeometrySegmentIndex", segment);
        ReflectionTestUtils.setField(leg, "progressFraction",
                fraction == null ? null : BigDecimal.valueOf(fraction));
        ReflectionTestUtils.setField(leg, "progressObservedAt", observedAt);
        return leg;
    }

    private static SegmentGeometry segment(int index, double fromX, double toX) {
        return new SegmentGeometry(index, null, null, null, 1000, 300, GeometryQuality.OK, null,
                List.of(new GeoPoint(fromX, 37.5), new GeoPoint(toX, 37.5)));
    }
}
