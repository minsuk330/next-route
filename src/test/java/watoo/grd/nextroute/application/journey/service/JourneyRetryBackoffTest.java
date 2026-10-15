package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties.RetryPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재시도 간격 정책(#451 PR-3)의 계약. FIXED 는 지금과 같고, EXPONENTIAL 은 첫 간격 × 2 씩 최대 60초, ±20% 지터를
 * [첫 간격, 최대] 로 자른다.
 */
@DisplayName("재시도 간격 — FIXED·EXPONENTIAL")
class JourneyRetryBackoffTest {

    private static JourneyNotificationProperties exponential() {
        JourneyNotificationProperties properties = new JourneyNotificationProperties();
        properties.setRetryPolicy(RetryPolicy.EXPONENTIAL);
        properties.setClaimTimeoutSeconds(45L);
        return properties;
    }

    @Test
    @DisplayName("첫 간격 기본값 = max(5초, FCM HTTP 최대 수명 22초 + 2초) = 24초")
    void defaultInitialFollowsHttpLifetime() {
        JourneyNotificationProperties properties = exponential();

        assertThat(properties.getFcm().httpMaxLifetime()).isEqualTo(Duration.ofSeconds(22));
        assertThat(properties.retryInitial()).isEqualTo(Duration.ofSeconds(24));

        properties.getFcm().setConnectTimeoutMs(100);
        properties.getFcm().setReadTimeoutMs(100);
        assertThat(properties.retryInitial()).as("수명이 짧으면 바닥 5초").isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("지터 0 이면 수열이 24 → 48 → 60 → 60 … 이다(최대 60초에서 멈춤)")
    void sequenceWithoutJitter() {
        JourneyNotificationProperties properties = exponential();
        properties.getRetry().setJitter(0);
        JourneyRetryBackoff backoff = new JourneyRetryBackoff(properties, new SplittableRandom(1));

        List<Long> seconds = new ArrayList<>();
        for (int attempt = 1; attempt <= 7; attempt++) {
            seconds.add(backoff.delayAfter(attempt).toSeconds());
        }

        assertThat(seconds).containsExactly(24L, 48L, 60L, 60L, 60L, 60L, 60L);
        assertThat(backoff.maxAttempts()).isEqualTo(8);
    }

    @Test
    @DisplayName("지터는 ±20% 안이고 [첫 간격, 최대] 로 잘린다 — 시드 고정이면 같은 수열")
    void jitterBounds() {
        JourneyNotificationProperties properties = exponential();
        properties.getRetry().setInitialMs(10_000L);
        JourneyRetryBackoff backoff = new JourneyRetryBackoff(properties, new SplittableRandom(42));
        JourneyRetryBackoff same = new JourneyRetryBackoff(properties, new SplittableRandom(42));

        boolean sawBelow = false;
        boolean sawAbove = false;
        for (int i = 0; i < 2_000; i++) {
            // 두 번째 시도 뒤 기준 20초 → [16, 24]
            long ms = backoff.delayAfter(2).toMillis();
            assertThat(ms).isBetween(16_000L, 24_000L);
            assertThat(same.delayAfter(2).toMillis()).as("같은 시드는 같은 값").isEqualTo(ms);
            sawBelow |= ms < 20_000;
            sawAbove |= ms > 20_000;
            // 첫 시도 뒤 기준 10초 → 아래는 첫 간격에서 잘려 [10, 12]
            long first = backoff.delayAfter(1).toMillis();
            assertThat(first).isBetween(10_000L, 12_000L);
            same.delayAfter(1);
            // 최대에 닿은 뒤 기준 60초 → 위는 최대에서 잘려 [48, 60]
            assertThat(backoff.delayAfter(6).toMillis()).isBetween(48_000L, 60_000L);
            same.delayAfter(6);
        }
        assertThat(sawBelow).as("지터가 실제로 아래로도 흔든다").isTrue();
        assertThat(sawAbove).as("지터가 실제로 위로도 흔든다").isTrue();
    }

    @Test
    @DisplayName("FIXED(기본)는 지금과 같다 — 늘 backoff-seconds, 상한 max-attempts, 난수를 쓰지 않는다")
    void fixedIsUnchanged() {
        JourneyNotificationProperties properties = new JourneyNotificationProperties();
        JourneyRetryBackoff backoff = new JourneyRetryBackoff(properties, () -> {
            throw new AssertionError("FIXED 는 난수를 쓰면 안 된다");
        });

        assertThat(properties.getRetryPolicy()).isEqualTo(RetryPolicy.FIXED);
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(backoff.delayAfter(attempt)).isEqualTo(Duration.ofSeconds(60));
        }
        assertThat(backoff.maxAttempts()).isEqualTo(3);
    }
}
