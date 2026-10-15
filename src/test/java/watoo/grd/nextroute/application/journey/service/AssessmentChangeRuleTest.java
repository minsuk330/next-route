package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("판정 변화 규칙")
class AssessmentChangeRuleTest {

    private static final Instant T = Instant.parse("2026-10-15T09:00:00Z");
    private static final Duration TOL = Duration.ofSeconds(30);

    @Test
    @DisplayName("예정 시각이 허용 폭(30초) 미만으로 흔들리면 같다, 30초면 바뀜")
    void timesWithinToleranceAreSame() {
        TransferRiskAssessment prev = a(TransferStatus.CATCHABLE, 0, CurrentLegArrival.Source.REALTIME_ARRIVAL);

        assertThat(AssessmentChangeRule.same(a(TransferStatus.CATCHABLE, 29, CurrentLegArrival.Source.REALTIME_ARRIVAL), prev, TOL)).isTrue();
        assertThat(AssessmentChangeRule.same(a(TransferStatus.CATCHABLE, 30, CurrentLegArrival.Source.REALTIME_ARRIVAL), prev, TOL)).isFalse();
    }

    @Test
    @DisplayName("상태·출처가 바뀌면 시각이 같아도 바뀜 — 위험 알림의 근거라 즉시")
    void statusOrSourceChangeIsAlwaysAChange() {
        TransferRiskAssessment prev = a(TransferStatus.CATCHABLE, 0, CurrentLegArrival.Source.REALTIME_ARRIVAL);

        assertThat(AssessmentChangeRule.same(a(TransferStatus.AT_RISK, 0, CurrentLegArrival.Source.REALTIME_ARRIVAL), prev, TOL)).isFalse();
        assertThat(AssessmentChangeRule.same(a(TransferStatus.CATCHABLE, 0, CurrentLegArrival.Source.MODEL_ARRIVAL), prev, TOL)).isFalse();
    }

    @Test
    @DisplayName("값이 생기거나 사라지면(모름 ↔ 앎) 바뀜")
    void nullToValueIsAChange() {
        TransferRiskAssessment known = a(TransferStatus.CATCHABLE, 0, CurrentLegArrival.Source.REALTIME_ARRIVAL);
        TransferRiskAssessment unknown = new TransferRiskAssessment(0, 1, 3, TransferStatus.UNKNOWN, null, null, null,
                null, CurrentLegArrival.Quality.GOOD, T, null, null);

        assertThat(AssessmentChangeRule.same(unknown, known, TOL)).isFalse();
        assertThat(AssessmentChangeRule.same(known, unknown, TOL)).isFalse();
        assertThat(AssessmentChangeRule.same(known, null, TOL)).isFalse();
    }

    @Test
    @DisplayName("조금씩 밀려도 결국 바뀜으로 잡힌다 — 폭 안이면 기준(저장값)을 옮기지 않으므로")
    void slowDriftEventuallyCounts() {
        Map<Integer, TransferRiskAssessment> stored = Map.of(0,
                a(TransferStatus.CATCHABLE, 0, CurrentLegArrival.Source.REALTIME_ARRIVAL));
        int changes = 0;
        for (int step = 1; step <= 8; step++) {
            // 평가마다 예정 시각이 5초씩 밀린다
            List<TransferRiskAssessment> now = List.of(a(TransferStatus.CATCHABLE, step * 5L,
                    CurrentLegArrival.Source.REALTIME_ARRIVAL));
            if (AssessmentChangeRule.anyChanged(now, stored, TOL)) {
                changes++;
            }
            stored = Map.of(0, AssessmentChangeRule.stabilize(now, stored, TOL).get(0));
        }
        // 기준을 매번 옮기면 5초 차이라 영영 0번이다. 기준이 고정되면 30초 넘을 때(6번째) 잡힌다
        assertThat(changes).isEqualTo(1);
        assertThat(stored.get(0).nextBusArrivalAt()).isEqualTo(T.plusSeconds(30));
    }

    @Test
    @DisplayName("폭 안이면 저장값은 직전 시각 그대로, 계산 시각만 새것 — 낡음 판정은 계속 새로워야 한다")
    void stabilizeKeepsValuesButRefreshesCalculatedAt() {
        TransferRiskAssessment prev = a(TransferStatus.CATCHABLE, 0, CurrentLegArrival.Source.REALTIME_ARRIVAL);
        TransferRiskAssessment now = new TransferRiskAssessment(0, 1, 3, TransferStatus.CATCHABLE,
                T.plusSeconds(10), T.plusSeconds(10), T.plusSeconds(10), 130L,
                CurrentLegArrival.Quality.GOOD, T.plusSeconds(60), CurrentLegArrival.Source.REALTIME_ARRIVAL, null);

        TransferRiskAssessment stored = AssessmentChangeRule.stabilize(List.of(now), Map.of(0, prev), TOL).get(0);

        assertThat(stored.nextBusArrivalAt()).isEqualTo(prev.nextBusArrivalAt());
        assertThat(stored.transferSlackSeconds()).isEqualTo(prev.transferSlackSeconds());
        assertThat(stored.calculatedAt()).isEqualTo(T.plusSeconds(60));
    }

    @Test
    @DisplayName("허용 폭 0 이면 예전처럼 정확히 같아야 같다(되돌리는 스위치)")
    void zeroToleranceIsExact() {
        Duration exact = Duration.ofNanos(1);
        TransferRiskAssessment prev = a(TransferStatus.CATCHABLE, 0, CurrentLegArrival.Source.REALTIME_ARRIVAL);

        assertThat(AssessmentChangeRule.same(a(TransferStatus.CATCHABLE, 1, CurrentLegArrival.Source.REALTIME_ARRIVAL), prev, exact)).isFalse();
        assertThat(AssessmentChangeRule.same(a(TransferStatus.CATCHABLE, 0, CurrentLegArrival.Source.REALTIME_ARRIVAL), prev, exact)).isTrue();
    }

    /** 예정 시각 셋을 T + shift 로 같이 옮긴 판정. 여유는 120 + shift. */
    private static TransferRiskAssessment a(TransferStatus status, long shiftSeconds, CurrentLegArrival.Source source) {
        Instant at = T.plusSeconds(shiftSeconds);
        return new TransferRiskAssessment(0, 1, 3, status, at, at, at, 120L + shiftSeconds,
                CurrentLegArrival.Quality.GOOD, T.plusSeconds(shiftSeconds), source, null);
    }
}
