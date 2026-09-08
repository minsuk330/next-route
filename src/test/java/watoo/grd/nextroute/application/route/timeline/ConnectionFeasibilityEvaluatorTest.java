package watoo.grd.nextroute.application.route.timeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 연결 가능성 판정 순수 계약. userReadyAt + buffer 이상 출발이어야 탈 수 있다. */
class ConnectionFeasibilityEvaluatorTest {

    private final ConnectionFeasibilityEvaluator evaluator = new ConnectionFeasibilityEvaluator();
    private final Instant ready = Instant.parse("2026-09-08T01:00:00Z");

    @Test
    @DisplayName("출발이 준비+buffer 이상 → FEASIBLE")
    void feasible() {
        assertThat(evaluator.evaluate(ready, ready.plusSeconds(120), 60, false))
                .isEqualTo(Feasibility.FEASIBLE);
    }

    @Test
    @DisplayName("출발이 준비+buffer 미만(여유 부족) → MISSED")
    void missedWithinBuffer() {
        assertThat(evaluator.evaluate(ready, ready.plusSeconds(30), 60, false))
                .isEqualTo(Feasibility.MISSED);
    }

    @Test
    @DisplayName("출발이 이미 준비 이전(지나감) → MISSED")
    void missedAlreadyGone() {
        assertThat(evaluator.evaluate(ready, ready.minusSeconds(10), 60, false))
                .isEqualTo(Feasibility.MISSED);
    }

    @Test
    @DisplayName("운행취소 → CANCELLED(여유 무관)")
    void cancelled() {
        assertThat(evaluator.evaluate(ready, ready.plusSeconds(600), 60, true))
                .isEqualTo(Feasibility.CANCELLED);
    }

    @Test
    @DisplayName("출발 == 준비+buffer 경계 → FEASIBLE(포함)")
    void boundaryInclusive() {
        assertThat(evaluator.evaluate(ready, ready.plusSeconds(60), 60, false))
                .isEqualTo(Feasibility.FEASIBLE);
    }
}
