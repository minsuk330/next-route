package watoo.grd.nextroute.application.journey.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 예정 출처·단계·근거 시각(지하철 링 TYPICAL 계약)이 모든 {@code with…()} 를 지나도 남는다. 하나라도 정규 생성자에 새 칸을
 * 빠뜨리면 조용히 null 이 된다 — 그러면 앱은 실시간 시각을 숨기지 못하고, 갱신기는 근거를 처음 본 시각을 잃는다.
 */
@DisplayName("링 상태 — 출처·단계·근거 시각 보존")
class SubwayRingStateSourceTest {

    private static final Instant T0 = Instant.parse("2026-10-10T12:00:00Z");

    @Test
    @DisplayName("with…() 전부가 다섯 칸을 그대로 넘긴다")
    void everyWitherKeepsSourceFields() {
        SubwayRingState base = base();
        List<UnaryOperator<SubwayRingState>> withers = List.of(
                s -> s.withProgressTarget(new BigDecimal("0.50")),
                s -> s.withProgressValidUntil(T0.plusSeconds(500)),
                s -> s.refreshed(T0.plusSeconds(5), T0.plusSeconds(6)),
                s -> s.withEstimatedAlightingAt(T0.plusSeconds(400)),
                s -> s.withGuidanceVersion(42L),
                s -> s.withHold(SubwayRingHoldReason.LATE, null),
                s -> s.withAnchor(new BigDecimal("0.60"), T0.plusSeconds(10)));

        for (UnaryOperator<SubwayRingState> wither : withers) {
            SubwayRingState out = wither.apply(base);
            assertThat(out.etaSource()).isEqualTo(SubwayRingEtaSource.MEASURED);
            assertThat(out.alightingEtaSource()).isEqualTo(SubwayRingEtaSource.TYPICAL);
            assertThat(out.stage()).isEqualTo(SubwayRingStage.RUN);
            assertThat(out.sourceObservedAt()).isEqualTo(T0.minusSeconds(80));
            assertThat(out.sourceSeenAt()).isEqualTo(T0.minusSeconds(5));
        }
    }

    @Test
    @DisplayName("자기 칸만 바꾼다 — 출처·단계, 근거 시각, 하차 예정과 그 출처")
    void ownFieldsOnly() {
        SubwayRingState base = base();

        SubwayRingState sourced = base.withSource(SubwayRingEtaSource.LIVE, SubwayRingStage.APPROACH);
        assertThat(sourced.etaSource()).isEqualTo(SubwayRingEtaSource.LIVE);
        assertThat(sourced.stage()).isEqualTo(SubwayRingStage.APPROACH);
        assertThat(sourced.sourceSeenAt()).isEqualTo(base.sourceSeenAt());
        assertThat(sourced.segmentProgress()).isEqualByComparingTo(base.segmentProgress());

        SubwayRingState timed = base.withSourceTimes(T0, T0.plusSeconds(3));
        assertThat(timed.sourceObservedAt()).isEqualTo(T0);
        assertThat(timed.sourceSeenAt()).isEqualTo(T0.plusSeconds(3));
        assertThat(timed.etaSource()).isEqualTo(base.etaSource());

        SubwayRingState noAlighting = base.withEstimatedAlightingAt(null, SubwayRingEtaSource.LIVE);
        assertThat(noAlighting.estimatedAlightingAt()).isNull();
        assertThat(noAlighting.alightingEtaSource()).as("하차 예정이 없으면 출처도 없다").isNull();
    }

    @Test
    @DisplayName("옛 생성자로 만든 값은 다섯 칸이 비어 있다")
    void oldConstructorLeavesThemEmpty() {
        SubwayRingState old = new SubwayRingState(1L, 1, 4, 0, 1, "k", "역A", "하차역", 3,
                new BigDecimal("0.30"), T0, new BigDecimal("0.90"), T0.plusSeconds(60), T0.plusSeconds(120),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, T0, T0);

        assertThat(old.etaSource()).isNull();
        assertThat(old.alightingEtaSource()).isNull();
        assertThat(old.stage()).isNull();
        assertThat(old.sourceObservedAt()).isNull();
        assertThat(old.sourceSeenAt()).isNull();
    }

    private static SubwayRingState base() {
        return new SubwayRingState(1L, 1, 4, 0, 1, "k", "역A", "하차역", 3,
                new BigDecimal("0.30"), T0, new BigDecimal("0.90"), T0.plusSeconds(60), T0.plusSeconds(120),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, T0, T0, 7L, T0.plusSeconds(300),
                null, null,
                SubwayRingEtaSource.MEASURED, SubwayRingEtaSource.TYPICAL, SubwayRingStage.RUN,
                T0.minusSeconds(80), T0.minusSeconds(5));
    }
}
