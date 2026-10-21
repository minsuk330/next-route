package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.journey.dto.SubwayRingContext;
import watoo.grd.nextroute.application.journey.dto.SubwayRingEtaSource;
import watoo.grd.nextroute.application.journey.dto.SubwayRingObservation;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.dto.SubwayRingStage;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalCode;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 급행이 지나치는 역 관측(subway-express PR-S4, codex 검토). 정차·도착이 아니라 구간 안 진행이다 — 목표는 앞으로만 당기고,
 * 구간 시작 시각은 그대로 둔 채 남은 시간만 고친다. {@code position(DEPARTED, k-1)} 로 바꾸면 안 된다는 것이 이 시험의 요점이다.
 */
@DisplayName("지하철 진행 링 — 급행이 지나치는 역")
class SubwayRingCalculatorPassingTest {

    private static final Instant T0 = Instant.parse("2026-10-21T12:40:00Z");

    private final JourneySubwayRingProperties properties = new JourneySubwayRingProperties();
    private final SubwayRingCalculator calculator = new SubwayRingCalculator(properties);

    /** 가양(0) →[증미·등촌]→ 염창(1) →[신목동·선유도]→ 당산(2). 급행 구간 운행: 가양→염창 210초, 염창→당산 150초. */
    private final SubwayRingContext context = new SubwayRingContext(7L, 1, 3, 0,
            new SubwayRingChain(List.of(
                    new SubwayRingChain.Station(0, "1009000907", "가양"),
                    new SubwayRingChain.Station(1, "1009000910", "염창", List.of("1009000908", "1009000909")),
                    new SubwayRingChain.Station(2, "1009000913", "당산", List.of("1009000911", "1009000912")))),
            1).withTypical(seq -> new SubwayRingContext.Typical(null, seq == 1 ? 210 : 150));

    private static SubwayRingObservation passing(int segment, String fraction, Instant passedAt, Instant observedAt) {
        return SubwayRingObservation.passing(segment, new BigDecimal(fraction), SubwayTrainStatus.DEPARTED,
                observedAt, passedAt);
    }

    @Test
    @DisplayName("도착 행이 없고 첫 관측이 지나치는 역 — 그 지점에서 남은 몫만큼 직선(구간 전체를 새로 세지 않는다)")
    void firstObservationAtPassingStation() {
        SubwayRingState state = calculator.next(null, context, passing(1, "0.40", T0, T0), T0);

        assertThat(state.nextStationSequence()).isEqualTo(1);
        assertThat(state.nextStationName()).isEqualTo("염창");
        assertThat(state.remainingStations()).as("정차역만 센다").isEqualTo(2);
        assertThat(state.segmentProgress()).isEqualByComparingTo("0.36");
        assertThat(state.progressBaseAt()).isEqualTo(T0);
        // 남은 60% × 210초 × 1.2 = 151.2초 — 지나친 시각부터 210×1.2=252초를 새로 세면 100초 늦다
        assertThat(state.estimatedNextStationAt()).isEqualTo(T0.plusMillis(151_200));
        assertThat(state.etaSource()).isEqualTo(SubwayRingEtaSource.TYPICAL);
        assertThat(state.stage()).isEqualTo(SubwayRingStage.RUN);
        assertThat(state.quality()).isEqualTo(SubwayRingQuality.NORMAL);
        assertThat(state.arrivedAtNextStation()).as("정차가 아니다").isFalse();
    }

    @Test
    @DisplayName("연속 지나치는 역 — 뒤 역이 더 앞선 지점이면 다시 앵커, 같은 역 반복은 그대로")
    void consecutivePassingStations() {
        SubwayRingState first = calculator.next(null, context, passing(1, "0.40", T0, T0), T0);
        Instant t1 = T0.plusSeconds(60);
        SubwayRingState second = calculator.next(first, context, passing(1, "0.70", t1, t1), t1);

        assertThat(second.nextStationSequence()).isEqualTo(1);
        assertThat(second.segmentProgress()).isEqualByComparingTo("0.63");
        assertThat(second.estimatedNextStationAt()).isEqualTo(t1.plusMillis(75_600)); // 30% × 210 × 1.2
        assertThat(second.shownProgressAt(t1)).isGreaterThanOrEqualTo(first.shownProgressAt(t1));

        Instant t2 = T0.plusSeconds(75);
        SubwayRingState repeated = calculator.next(second, context, passing(1, "0.70", t1, t2), t2);
        assertThat(repeated.segmentProgress()).isEqualByComparingTo(second.segmentProgress());
        assertThat(repeated.progressBaseAt()).isEqualTo(second.progressBaseAt());
        assertThat(repeated.estimatedNextStationAt()).as("반복 관측이 예정을 밀지 않는다")
                .isEqualTo(second.estimatedNextStationAt());
    }

    @Test
    @DisplayName("지나친 직후 정차역 도착 — 1.00, 다음 tick 에 다음 구간")
    void passingThenStopArrival() {
        SubwayRingState passed = calculator.next(null, context, passing(1, "0.70", T0, T0), T0);
        Instant t1 = T0.plusSeconds(50);
        SubwayRingState arrived = calculator.next(passed, context,
                SubwayRingObservation.position(SubwayTrainStatus.ARRIVED, 1, t1), t1);

        assertThat(arrived.nextStationSequence()).isEqualTo(1);
        assertThat(arrived.arrivedAtNextStation()).isTrue();
        assertThat(calculator.targetStationSequence(arrived, context)).isEqualTo(2);
    }

    @Test
    @DisplayName("#483 진입 직선 뒤 늦게 온 지나치는 역 관측은 진입을 되돌리지 않는다 — 램프 앵커(0.95 아님)를 진행으로 읽지 않는다")
    void latePassingAfterApproachKeepsTheRamp() {
        // 염창 구간 운행 직선 0.36(지나친 40%) → 진입: 화면 값에서 0.95 까지 램프
        SubwayRingState passed = calculator.next(null, context, passing(1, "0.40", T0, T0), T0);
        Instant t1 = T0.plusSeconds(40);
        SubwayRingState entering = calculator.next(passed, context,
                SubwayRingObservation.arrival(SubwayArrivalCode.ENTERING, 1, null, t1), t1);
        assertThat(entering.stage()).isEqualTo(SubwayRingStage.APPROACH);
        // 화면 = 0.36 + 0.54 × 40/151.2 ≈ 0.50. 아래 지나침 지점(0.63)보다 낮다
        assertThat(entering.segmentProgress()).as("램프 앵커는 화면 값").isLessThan(new BigDecimal("0.63"));

        // 늦게 온 "70% 지나침" — 램프 앵커보다 앞선 지점이지만 열차는 이미 진입했다
        Instant t2 = t1.plusSeconds(2);
        SubwayRingState late = calculator.next(entering, context, passing(1, "0.70", T0.plusSeconds(30), t2), t2);

        assertThat(late.stage()).isEqualTo(SubwayRingStage.APPROACH);
        assertThat(late.estimatedNextStationAt()).isEqualTo(entering.estimatedNextStationAt());
        assertThat(late.progressBaseAt()).isEqualTo(entering.progressBaseAt());
        assertThat(late.shownProgressAt(t1.plus(properties.getApproachRamp()))).isEqualByComparingTo("0.95");
    }

    @Test
    @DisplayName("되돌아가지 않는다 — 앞 구간의 지나치는 역 관측은 지금 목표를 바꾸지 않는다")
    void neverMovesBackwards() {
        SubwayRingState atSecondSegment = calculator.next(null, context, passing(2, "0.50", T0, T0), T0);
        Instant t1 = T0.plusSeconds(20);
        SubwayRingState state = calculator.next(atSecondSegment, context, passing(1, "0.90", T0, t1), t1);

        assertThat(atSecondSegment.nextStationSequence()).isEqualTo(2);
        assertThat(state.nextStationSequence()).isEqualTo(2);
        assertThat(state.shownProgressAt(t1)).isGreaterThanOrEqualTo(atSecondSegment.shownProgressAt(t1));
    }

    @Test
    @DisplayName("정차역 도착을 놓쳤으면 지나치는 역이 가리키는 구간으로 앞으로 건너뛴다")
    void skipsForwardToPassingSegment() {
        SubwayRingState first = calculator.next(null, context, passing(1, "0.40", T0, T0), T0);
        Instant t1 = T0.plusSeconds(200);
        SubwayRingState state = calculator.next(first, context, passing(2, "0.33", t1, t1), t1);

        assertThat(state.nextStationSequence()).isEqualTo(2);
        assertThat(state.remainingStations()).isEqualTo(1);
        assertThat(state.segmentProgress()).isEqualByComparingTo("0.2970");
    }

    @Test
    @DisplayName("급행 구간 시간을 모르면 그 지점에 세운다(사실이라 NORMAL, 예정 없음)")
    void holdsAtPointWithoutTypical() {
        SubwayRingContext unknown = context.withTypical(seq -> null);
        SubwayRingState state = calculator.next(null, unknown, passing(1, "0.50", T0, T0), T0);

        assertThat(state.segmentProgress()).isEqualByComparingTo("0.45");
        assertThat(state.progressTarget()).isEqualByComparingTo("0.45");
        assertThat(state.estimatedNextStationAt()).isNull();
        assertThat(state.quality()).isEqualTo(SubwayRingQuality.NORMAL);
    }

    @Test
    @DisplayName("남은 몫의 예정이 유예까지 지났으면 상한에서 LATE")
    void lateAfterRemainingShare() {
        SubwayRingState state = calculator.next(null, context, passing(1, "0.90", T0, T0), T0.plusSeconds(90));

        assertThat(state.stage()).isEqualTo(SubwayRingStage.LATE);
        assertThat(state.nextStationSequence()).isEqualTo(1);
    }

    @Test
    @DisplayName("마지막 지나친 역 출발(3) + 실측 — 그 역 지점을 진행 하한으로 실측 직선")
    void measuredFromLastPassingUsesFloor() {
        SubwayRingObservation measured = SubwayRingObservation.measured(1, T0, T0.plusSeconds(60), T0)
                .withPassingFraction(new BigDecimal("0.80"));

        SubwayRingState state = calculator.next(null, context, measured, T0);

        assertThat(state.segmentProgress()).isEqualByComparingTo("0.72");
        assertThat(state.estimatedNextStationAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(state.etaSource()).isEqualTo(SubwayRingEtaSource.MEASURED);
    }

    @Test
    @DisplayName("일반 열차 관측(지나치는 역 없음)은 예전과 같다 — 전역 출발 실측은 0 에서")
    void normalMeasuredUnchanged() {
        SubwayRingState state = calculator.next(null, context,
                SubwayRingObservation.measured(1, T0, T0.plusSeconds(60), T0), T0);

        assertThat(state.segmentProgress()).isEqualByComparingTo("0");
        assertThat(state.estimatedNextStationAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(SubwayArrivalCode.PREV_DEPARTED.isPreviousStation()).isTrue();
    }
}
