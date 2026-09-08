package watoo.grd.nextroute.application.route.timeline;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 한 편의 연결 가능성 판정(순수). "선택편 출발이 userReadyAt + boardingBuffer 이상인가".
 * buffer 는 이 판정에만 쓰이고 WAIT 에 이미 포함되므로 총시간에 따로 더하지 않는다(중복 가산 금지).
 */
@Component
public class ConnectionFeasibilityEvaluator {

    public Feasibility evaluate(Instant userReadyAt, Instant effectiveDepartureAt,
                                long bufferSeconds, boolean cancelled) {
        if (cancelled) {
            return Feasibility.CANCELLED;
        }
        if (effectiveDepartureAt == null) {
            return Feasibility.MISSED;
        }
        Instant deadline = userReadyAt.plusSeconds(Math.max(0, bufferSeconds));
        return effectiveDepartureAt.isBefore(deadline) ? Feasibility.MISSED : Feasibility.FEASIBLE;
    }
}
