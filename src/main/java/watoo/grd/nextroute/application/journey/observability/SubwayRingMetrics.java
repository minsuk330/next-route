package watoo.grd.nextroute.application.journey.observability;

import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.SubwayRingObservation;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPushReason;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;

import java.time.Duration;

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

    public static final String PUSH_ENQUEUED = "journey.subway.ring.push.enqueued";
    public static final String PUSH_SKIPPED_ENQUEUE = "journey.subway.ring.push.skipped_enqueue";
    public static final String PUSH_RESULT = "journey.subway.ring.push";
    public static final String PUSH_RETRY = "journey.subway.ring.push.retry";
    public static final String PUSH_LATENCY = "journey.subway.ring.push.latency";

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
    public void recordUpdated(SubwayRingState state, SubwayRingObservation observation,
                              SubwayBindingStatus bindingStatus) {
        // 잠정 링이 얼마나 그려지는지 — 화면은 확정과 구분하지 않지만 서버는 사실을 남긴다.
        registry.counter(UPDATED, "quality", state.quality().name().toLowerCase(),
                "binding", bindingStatus.name().toLowerCase()).increment();
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

    // ── 원격 갱신(push) ─────────────────────────────────────────────────────

    /**
     * 대기열에 넣으려 했다.
     *
     * <p>{@code accepted=false} 는 경합이다 — 두 워커가 같은 여정을 동시에 계산했고 한쪽이 졌다.
     * 실패가 아니므로 오류로 세지 않는다.
     */
    public void recordPushEnqueued(SubwayRingPushReason reason, boolean accepted) {
        registry.counter(PUSH_ENQUEUED,
                "reason", reason.tag(),
                "accepted", String.valueOf(accepted)).increment();
    }

    /**
     * 넣기 전에 걸렀다.
     *
     * <p>{@code same_signature} 가 대부분이어야 정상이다 — 링은 5초마다 도는데 화면은 그보다
     * 훨씬 덜 바뀐다. 이 값이 낮으면 signature 가 너무 잘게 갈리고 있다는 뜻이고, 그만큼 잠금화면
     * 갱신이 배터리와 APNs 예산을 먹는다.
     */
    public void recordPushSkippedEnqueue(String reason) {
        registry.counter(PUSH_SKIPPED_ENQUEUE, "reason", reason).increment();
    }

    /**
     * 발송 한 건의 결말.
     *
     * <p>{@code skipped} 는 실패가 아니다. 링 갱신에서 가장 흔한 정상 결말이고, 오히려 그 수가
     * 많다는 것은 원천이 살아 있다는 뜻이다.
     */
    public void recordPushResult(String result, String reason) {
        registry.counter(PUSH_RESULT, "result", result, "reason", reason).increment();
    }

    /** 최신인 동안의 재시도가 얼마나 도는지. {@code critical} 이 아닌 재시도가 많으면 낭비다. */
    public void recordPushRetry(boolean critical) {
        registry.counter(PUSH_RETRY, "critical", String.valueOf(critical)).increment();
    }

    /** 링 계산에서 실제 발송까지 걸린 시간. */
    public void recordPushLatency(Duration latency) {
        if (!latency.isNegative()) {
            registry.timer(PUSH_LATENCY).record(latency);
        }
    }
}
