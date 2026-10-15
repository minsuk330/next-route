package watoo.grd.nextroute.application.journey.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties.RetryPolicy;

import java.time.Duration;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * 일시 실패 뒤 다음 시도까지의 간격과 시도 상한(#451 PR-3).
 *
 * <ul>
 *   <li>{@code FIXED}(기본) — 늘 {@code backoff-seconds}, 상한 {@code max-attempts}. PR-3 이전과 같다</li>
 *   <li>{@code EXPONENTIAL} — n 번째 실제 시도가 실패하면 min(최대, 첫 간격 × 배수^(n−1)) × (1 ± 지터), 결과를
 *       [첫 간격, 최대] 로 자른다. 상한 {@code retry.max-attempts-cap}</li>
 * </ul>
 *
 * <p>만료를 넘기는 다음 시도는 여기서 막지 않는다 — 호출자({@code markTransientFailure})가 기존
 * {@code expired-before-retry} 취소 경로로 끝낸다.
 */
@Component
public class JourneyRetryBackoff {

    private final JourneyNotificationProperties properties;
    private final RandomGenerator random;

    /** 운영용. {@link Random} 은 여러 relay 워커가 같이 불러도 안전하다(내부 CAS). */
    @Autowired
    public JourneyRetryBackoff(JourneyNotificationProperties properties) {
        this(properties, new Random());
    }

    /** 시험용 — 시드 고정 난수로 지터를 결정적으로 만든다. */
    public JourneyRetryBackoff(JourneyNotificationProperties properties, RandomGenerator random) {
        this.properties = properties;
        this.random = random;
    }

    /** 실제 발송 시도({@code attempt_count − requeue_count}) 상한. 이 수에 닿으면 DEAD. */
    public int maxAttempts() {
        return properties.retryAttemptCap();
    }

    /**
     * @param sendAttempts 방금 실패한 시도까지 센 실제 발송 시도 수(1 이상)
     * @return 다음 시도까지의 간격
     */
    public Duration delayAfter(int sendAttempts) {
        if (properties.getRetryPolicy() != RetryPolicy.EXPONENTIAL) {
            return properties.backoff();
        }
        JourneyNotificationProperties.Retry retry = properties.getRetry();
        long initialMs = properties.retryInitial().toMillis();
        long maxMs = properties.retryMax().toMillis();
        int exponent = Math.max(0, sendAttempts - 1);
        double base = Math.min((double) maxMs, initialMs * Math.pow(retry.getMultiplier(), exponent));
        double factor = 1.0 + retry.getJitter() * (2.0 * random.nextDouble() - 1.0);
        long jittered = Math.round(base * factor);
        return Duration.ofMillis(Math.max(initialMs, Math.min(maxMs, jittered)));
    }
}
