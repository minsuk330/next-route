package watoo.grd.nextroute.application.journey.observability;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.reeval.ReevaluationTier;

/**
 * 이벤트 기반 재평가(#415-3) 지표. tag 는 고정 집합(등급·사유)만 쓴다.
 */
@Component
@RequiredArgsConstructor
public class JourneyReevalMetrics {

    public static final String PULLED = "journey.reeval.pulled";
    public static final String DIRTY_MARKED = "journey.reeval.dirty-marked";
    public static final String DEFERRED = "journey.reeval.deferred";
    public static final String SHADOW = "journey.reeval.shadow";

    private final MeterRegistry registry;

    /** 처리 중이 아니라 다음 평가 시각을 직접 앞당긴 여정 수. */
    public void recordPulled(ReevaluationTier tier, int count) {
        if (count > 0) {
            registry.counter(PULLED, "tier", tag(tier)).increment(count);
        }
    }

    /** 처리 중이라 dirty 표시만 한 여정 수. 늘면 이벤트가 평가 시간보다 잦다는 뜻이다. */
    public void recordDirtyMarked(ReevaluationTier tier, int count) {
        if (count > 0) {
            registry.counter(DIRTY_MARKED, "tier", tag(tier)).increment(count);
        }
    }

    /**
     * 반영하지 못하고 미룬 여정 수.
     *
     * @param reason {@code overflow}(요청 상한 초과)·{@code shed}(평가기 포화)·{@code failed}(DB 실패)
     */
    public void recordDeferred(String reason, int count) {
        if (count > 0) {
            registry.counter(DEFERRED, "reason", reason).increment(count);
        }
    }

    /** 그림자 모드에서 반영했다고 친 여정 수. 켰을 때 평가기에 얹힐 일의 크기다. */
    public void recordShadow(ReevaluationTier tier, int count) {
        if (count > 0) {
            registry.counter(SHADOW, "tier", tag(tier)).increment(count);
        }
    }

    private static String tag(ReevaluationTier tier) {
        return tier.name().toLowerCase();
    }
}
