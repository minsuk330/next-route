package watoo.grd.nextroute.application.journey.observability;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingDecision;

/**
 * 탑승 열차 판정 지표.
 *
 * <p>가장 중요한 것은 <b>"왜 고정하지 않았나"</b>다. 관문 숫자는 전부 잠정값이라 실측으로 조여야
 * 하는데, 사유 없이 실패만 세면 몇 번 못 골랐는지는 알아도 어느 관문이 막았는지는 영영 모른다.
 *
 * <p>tag 는 고정 집합이다 — 사유는 판정기가 만드는 짧은 어휘뿐이고 여정 번호·열차번호처럼
 * 요청마다 달라지는 값은 넣지 않는다. 넣으면 시계열이 무한히 늘어난다.
 */
@Component
@RequiredArgsConstructor
public class SubwayBindingMetrics {

    public static final String DECISIONS = "journey.subway.binding.decisions";
    public static final String APPLIED = "journey.subway.binding.applied";
    public static final String SCANNED = "journey.subway.binding.scanned";
    public static final String FAILURES = "journey.subway.binding.failures";

    private final MeterRegistry registry;

    /** 판정 한 번의 결말. {@code NONE} 은 사유까지 갈라 센다. */
    public void recordDecision(SubwayBindingDecision decision) {
        registry.counter(DECISIONS,
                "kind", decision.kind().name().toLowerCase(),
                "reason", decision.reason()).increment();
    }

    /**
     * CAS 까지 실제로 반영됐는지.
     *
     * <p>{@link #DECISIONS} 와 벌어지는 폭이 곧 경합의 양이다 — 판정은 났는데 반영이 안 됐다는 뜻.
     */
    public void recordApplied(boolean applied) {
        registry.counter(APPLIED, "applied", String.valueOf(applied)).increment();
    }

    /** 이번 tick 이 훑은 건수. 배치 상한에 계속 닿으면 적체가 쌓이고 있다는 신호다. */
    public void recordScanned(int count) {
        if (count > 0) {
            registry.counter(SCANNED).increment(count);
        }
    }

    /** 한 건이 예외로 끝났다. 다른 건은 계속 처리된다. */
    public void recordFailure(String reason) {
        registry.counter(FAILURES, "reason", reason).increment();
    }
}
