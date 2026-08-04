package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeoPoint;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeometryQuality;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.SegmentGeometry;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("정적 geometry 진행도로 남은 시간을 구한다")
class StaticGeometryProgressEtaCalculatorTest {

    private final StaticGeometryProgressEtaCalculator calculator =
            new StaticGeometryProgressEtaCalculator();

    /** 구간 3개: 90초/100m, 360초/400m, 150초/200m. 합 600초. */
    private final List<SegmentGeometry> segments = List.of(
            segment(0, 100.0, 90),
            segment(1, 400.0, 360),
            segment(2, 200.0, 150));

    @Test
    @DisplayName("첫 구간 시작이면 전체가 남는다")
    void wholeLegRemainsAtStart() {
        assertThat(calculator.remainingDuration(segments, 0, BigDecimal.ZERO))
                .isEqualTo(Duration.ofSeconds(600));
        assertThat(calculator.remainingDistanceMeters(segments, 0, BigDecimal.ZERO))
                .isEqualTo(700);
    }

    @Test
    @DisplayName("현재 구간을 반 지났으면 그 구간 시간의 절반만 남는다")
    void prorratesCurrentSegment() {
        // 두 번째 구간 50% → 360×0.5 + 150 = 330
        assertThat(calculator.remainingDuration(segments, 1, BigDecimal.valueOf(0.5)))
                .isEqualTo(Duration.ofSeconds(330));
        // 400×0.5 + 200 = 400
        assertThat(calculator.remainingDistanceMeters(segments, 1, BigDecimal.valueOf(0.5)))
                .isEqualTo(400);
    }

    @Test
    @DisplayName("마지막 구간을 다 지났으면 0이다")
    void zeroAtEnd() {
        assertThat(calculator.remainingDuration(segments, 2, BigDecimal.ONE))
                .isEqualTo(Duration.ZERO);
        assertThat(calculator.remainingDistanceMeters(segments, 2, BigDecimal.ONE)).isZero();
    }

    @Test
    @DisplayName("구간 단위로만 세지 않는다 — 같은 구간 안에서도 남은 시간이 줄어든다")
    void decreasesWithinSegment() {
        Duration at10 = calculator.remainingDuration(segments, 1, BigDecimal.valueOf(0.1));
        Duration at90 = calculator.remainingDuration(segments, 1, BigDecimal.valueOf(0.9));

        assertThat(at90).isLessThan(at10);
    }

    @Test
    @DisplayName("진행률이 0~1 밖이어도 계산이 뒤집히지 않는다")
    void clampsFraction() {
        assertThat(calculator.remainingDuration(segments, 1, BigDecimal.valueOf(-3)))
                .isEqualTo(calculator.remainingDuration(segments, 1, BigDecimal.ZERO));
        assertThat(calculator.remainingDuration(segments, 1, BigDecimal.valueOf(5)))
                .isEqualTo(calculator.remainingDuration(segments, 1, BigDecimal.ONE));
    }

    @Test
    @DisplayName("구간 순번이 범위를 벗어나도 앞뒤로 잘라 맞춘다")
    void clampsIndex() {
        assertThat(calculator.remainingDuration(segments, -5, BigDecimal.ZERO))
                .isEqualTo(Duration.ofSeconds(600));
        assertThat(calculator.remainingDuration(segments, 99, BigDecimal.ONE))
                .isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("배분 시간이 없는 구간은 0초로 센다")
    void treatsMissingSecondsAsZero() {
        List<SegmentGeometry> partial = List.of(
                segment(0, 100.0, null),
                segment(1, 400.0, 360));

        assertThat(calculator.remainingDuration(partial, 0, BigDecimal.ZERO))
                .isEqualTo(Duration.ofSeconds(360));
    }

    @Test
    @DisplayName("구간이 없으면 0이다")
    void emptySegments() {
        assertThat(calculator.remainingDuration(List.of(), 0, BigDecimal.ZERO)).isEqualTo(Duration.ZERO);
        assertThat(calculator.remainingDuration(null, 0, BigDecimal.ZERO)).isEqualTo(Duration.ZERO);
        assertThat(calculator.remainingDistanceMeters(null, 0, BigDecimal.ZERO)).isZero();
    }

    @Test
    @DisplayName("도착 시각은 서버의 지금이 아니라 관측 시각에 더한다")
    void anchorsArrivalToObservation() {
        Instant observedAt = Instant.parse("2026-08-04T09:00:00Z");

        Instant arrival = calculator.arrivalAt(observedAt, Duration.ofSeconds(330));

        assertThat(arrival).isEqualTo(Instant.parse("2026-08-04T09:05:30Z"));
    }

    private SegmentGeometry segment(int index, double meters, Integer seconds) {
        return new SegmentGeometry(index, null, null, null, meters, seconds,
                GeometryQuality.OK, null,
                List.of(new GeoPoint(127.0, 37.5), new GeoPoint(127.001, 37.5)));
    }
}
