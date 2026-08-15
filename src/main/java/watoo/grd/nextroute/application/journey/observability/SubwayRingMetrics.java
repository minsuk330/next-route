package watoo.grd.nextroute.application.journey.observability;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.SubwayRingObservation;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;

/**
 * 진행 링 지표.
 *
 * <p>가장 중요한 것은 <b>{@code STALE} 비율</b>이다. 그 값이 곧 실시간 원천의 신선도이고,
 * 링이 죽어 있는 시간의 비율이다. 그다음이 <b>남은 초를 어디서 얻었나</b> — 도착 API 가 열차의
 * 3~8% 를 커버하지 못한다는 것이 실측이라, 이 비율이 예상보다 나쁘면 수집기나 역 해석이 깨진
 * 것이다.
 *
 * <p>tag 는 고정 집합이다. 여정 번호·열차번호처럼 요청마다 달라지는 값은 넣지 않는다 — 넣으면
 * 시계열이 무한히 늘어난다.
 */
@Component
@RequiredArgsConstructor
public class SubwayRingMetrics {

    public static final String SCANNED = "journey.subway.ring.scanned";
    public static final String UPDATED = "journey.subway.ring.updated";
    public static final String ETA = "journey.subway.ring.eta";
    public static final String FAILURES = "journey.subway.ring.failures";
    public static final String CLEARED = "journey.subway.ring.cleared";

    private final MeterRegistry registry;

    /** 이번 tick 이 훑은 확정 고정 수. 배치 상한에 계속 닿으면 적체다. */
    public void recordScanned(int count) {
        if (count > 0) {
            registry.counter(SCANNED).increment(count);
        }
    }

    /**
     * 링 하나를 갱신했다.
     *
     * <p>{@code quality} 와 {@code source} 를 함께 센다. {@code STALE} 이 많은데 원천이
     * {@code arrival} 이면 신호가 늦은 것이고, {@code position} 이면 그 열차가 도착 목록에
     * 아예 없는 것이다 — 대응이 다르다.
     */
    public void recordUpdated(SubwayRingState state, SubwayRingObservation observation) {
        registry.counter(UPDATED, "quality", state.quality().name().toLowerCase()).increment();
        registry.counter(ETA, "source", sourceOf(observation)).increment();
    }

    private String sourceOf(SubwayRingObservation observation) {
        if (observation == null) {
            return "none";
        }
        return observation.source().name().toLowerCase();
    }

    /** 한 건이 예외로 끝났다. 다른 건은 계속 처리된다. */
    public void recordFailure(String reason) {
        registry.counter(FAILURES, "reason", reason).increment();
    }

    /** 수명주기가 지운 링 키. 0 에 붙어 있으면 정리 경로가 새고 있다는 뜻이다. */
    public void recordCleared(int count) {
        if (count > 0) {
            registry.counter(CLEARED).increment(count);
        }
    }
}
