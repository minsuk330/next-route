package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneyNavigationProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Quality;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Source;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeoPoint;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeometryQuality;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.SegmentGeometry;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("이동 단계 ETA 는 앱 GPS 진행도로 구한다")
class ClientGeometryProgressArrivalPredictorTest {

    private static final Instant NOW = Instant.parse("2026-08-04T09:10:00Z");

    private final JourneyNavigationProperties properties = new JourneyNavigationProperties();
    private final ClientGeometryProgressArrivalPredictor predictor =
            new ClientGeometryProgressArrivalPredictor(
                    new StaticGeometryProgressEtaCalculator(), properties);

    @Test
    @DisplayName("이동 단계 네 종류를 맡고 기다리는 단계는 맡지 않는다")
    void supportsMovingLegsOnly() {
        assertThat(predictor.supports(JourneyLegType.RIDE_BUS)).isTrue();
        assertThat(predictor.supports(JourneyLegType.RIDE_SUBWAY)).isTrue();
        assertThat(predictor.supports(JourneyLegType.WALK)).isTrue();
        assertThat(predictor.supports(JourneyLegType.TRANSFER_WALK)).isTrue();

        assertThat(predictor.supports(JourneyLegType.WAIT_BUS)).isFalse();
        assertThat(predictor.supports(JourneyLegType.WAIT_SUBWAY)).isFalse();
    }

    @Test
    @DisplayName("최근 관측이면 관측 시각 기준으로 절대 ETA 를 고정한다")
    void fixesArrivalToObservation() {
        // 관측 30초 전, 두 번째 구간 50% → 남은 330초
        Instant observedAt = NOW.minusSeconds(30);
        JourneyLeg leg = leg(JourneyLegType.RIDE_BUS, observedAt, 1, 0.5, null, null);

        CurrentLegArrival arrival = predictor.predict(leg, geometry(GeometryQuality.OK), NOW);

        assertThat(arrival.source()).isEqualTo(Source.CLIENT_GEOMETRY_PROGRESS);
        assertThat(arrival.quality()).isEqualTo(Quality.GOOD);
        assertThat(arrival.remainingSeconds()).isEqualTo(330);
        // 지금(09:10:00) + 330 이 아니라 관측(09:09:30) + 330 이다
        assertThat(arrival.arrivalAt()).isEqualTo(Instant.parse("2026-08-04T09:15:00Z"));
        assertThat(arrival.remainingDistanceMeters()).isEqualTo(400);
    }

    @Test
    @DisplayName("서버 시각이 흘러도 같은 관측이면 도착 시각이 밀리지 않는다")
    void arrivalDoesNotDriftWithServerClock() {
        Instant observedAt = NOW.minusSeconds(30);
        JourneyLeg leg = leg(JourneyLegType.RIDE_BUS, observedAt, 1, 0.5, null, null);

        Instant first = predictor.predict(leg, geometry(GeometryQuality.OK), NOW).arrivalAt();
        Instant later = predictor.predict(leg, geometry(GeometryQuality.OK),
                NOW.plusSeconds(20)).arrivalAt();

        assertThat(later).isEqualTo(first);
    }

    @Test
    @DisplayName("관측이 오래되면 마지막으로 고정한 ETA 를 유지하고 품질만 낮춘다")
    void keepsLastArrivalWhenStale() {
        Instant observedAt = NOW.minusSeconds(300);
        Instant lastFixed = Instant.parse("2026-08-04T09:12:00Z");
        JourneyLeg leg = leg(JourneyLegType.RIDE_SUBWAY, observedAt, 1, 0.5, lastFixed, 120);

        CurrentLegArrival arrival = predictor.predict(leg, geometry(GeometryQuality.OK), NOW);

        assertThat(arrival.arrivalAt()).isEqualTo(lastFixed);
        assertThat(arrival.remainingSeconds()).isEqualTo(120);
        assertThat(arrival.quality()).isEqualTo(Quality.APPROXIMATE);
    }

    @Test
    @DisplayName("오래된 관측에 고정 ETA 가 없으면 관측 시각 기준으로 만든다. 지금 시각은 쓰지 않는다")
    void staleWithoutFixedArrivalUsesObservation() {
        Instant observedAt = NOW.minusSeconds(300);
        JourneyLeg leg = leg(JourneyLegType.RIDE_BUS, observedAt, 1, 0.5, null, 200);

        CurrentLegArrival arrival = predictor.predict(leg, geometry(GeometryQuality.OK), NOW);

        assertThat(arrival.arrivalAt()).isEqualTo(observedAt.plusSeconds(200));
        assertThat(arrival.quality()).isEqualTo(Quality.APPROXIMATE);
    }

    @Test
    @DisplayName("관측이 아예 없으면 계획 소요시간으로 근사한다")
    void fallsBackToPlannedDuration() {
        JourneyLeg leg = leg(JourneyLegType.WALK, null, null, null, null, null);
        ReflectionTestUtils.setField(leg, "activatedAt", NOW.minusSeconds(60));

        CurrentLegArrival arrival = predictor.predict(leg, geometry(GeometryQuality.OK), NOW);

        assertThat(arrival.source()).isEqualTo(Source.PLANNED_DURATION);
        assertThat(arrival.quality()).isEqualTo(Quality.APPROXIMATE);
        // 시작(09:09:00) + 계획 600초
        assertThat(arrival.arrivalAt()).isEqualTo(Instant.parse("2026-08-04T09:19:00Z"));
    }

    @Test
    @DisplayName("시작도 안 했고 진행도도 없으면 모른다고 말한다")
    void unknownWhenNothingToGoOn() {
        JourneyLeg leg = leg(JourneyLegType.WALK, null, null, null, null, null);

        CurrentLegArrival arrival = predictor.predict(leg, geometry(GeometryQuality.OK), NOW);

        assertThat(arrival.isUnknown()).isTrue();
        assertThat(arrival.arrivalAt()).isNull();
    }

    @Test
    @DisplayName("좌표가 직선 근사면 ETA 품질도 함께 낮춘다")
    void inheritsGeometryQuality() {
        JourneyLeg leg = leg(JourneyLegType.WALK, NOW.minusSeconds(10), 0, 0.0, null, null);

        CurrentLegArrival arrival = predictor.predict(leg, geometry(GeometryQuality.APPROXIMATE), NOW);

        assertThat(arrival.quality()).isEqualTo(Quality.APPROXIMATE);
    }

    @Test
    @DisplayName("좌표가 없으면 계획 소요시간으로 떨어진다")
    void noGeometryFallsBack() {
        JourneyLeg leg = leg(JourneyLegType.RIDE_BUS, NOW.minusSeconds(10), 0, 0.0, null, null);
        ReflectionTestUtils.setField(leg, "activatedAt", NOW.minusSeconds(60));

        CurrentLegArrival arrival = predictor.predict(leg, null, NOW);

        assertThat(arrival.source()).isEqualTo(Source.PLANNED_DURATION);
    }

    @Test
    @DisplayName("남은 정류장 수는 앱이 보낸 값을 그대로 전달한다")
    void passesRemainingStopsThrough() {
        JourneyLeg leg = leg(JourneyLegType.RIDE_BUS, NOW.minusSeconds(10), 1, 0.5, null, null);
        ReflectionTestUtils.setField(leg, "remainingStops", 3);

        assertThat(predictor.predict(leg, geometry(GeometryQuality.OK), NOW).remainingStops())
                .isEqualTo(3);
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private JourneyLeg leg(JourneyLegType type, Instant observedAt, Integer segmentIndex,
                           Double segmentProgress, Instant predictedEndAt, Integer remainingSeconds) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(1L)
                .routeRevision(1)
                .legIndex(0)
                .type(type)
                .state(JourneyLegState.ACTIVE)
                .plannedDurationSec(600)
                .build();
        ReflectionTestUtils.setField(leg, "id", 10L);
        ReflectionTestUtils.setField(leg, "progressObservedAt", observedAt);
        ReflectionTestUtils.setField(leg, "progressGeometrySegmentIndex", segmentIndex);
        ReflectionTestUtils.setField(leg, "progressFraction",
                segmentProgress == null ? null : BigDecimal.valueOf(segmentProgress));
        ReflectionTestUtils.setField(leg, "predictedEndAt", predictedEndAt);
        ReflectionTestUtils.setField(leg, "remainingDurationSeconds", remainingSeconds);
        ReflectionTestUtils.setField(leg, "remainingDistanceMeters", 1000);
        return leg;
    }

    /** 구간 3개: 90초/100m, 360초/400m, 150초/200m. */
    private LegGeometry geometry(GeometryQuality quality) {
        List<SegmentGeometry> segments = List.of(
                segment(0, 100.0, 90), segment(1, 400.0, 360), segment(2, 200.0, 150));
        return new LegGeometry(0, JourneyLegType.RIDE_BUS, "R1", 600, 700.0,
                quality, List.of(), segments, List.of());
    }

    private SegmentGeometry segment(int index, double meters, int seconds) {
        return new SegmentGeometry(index, null, null, null, meters, seconds,
                GeometryQuality.OK, null,
                List.of(new GeoPoint(127.0, 37.5), new GeoPoint(127.001, 37.5)));
    }
}
