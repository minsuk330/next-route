package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisBusApiBreakerTest {

    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    static final Instant NOW = Instant.parse("2026-06-06T04:00:00Z");
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    RedisBusApiBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = new RedisBusApiBreaker(redisTemplate, clock);
    }

    @Test
    void TC_차단_없음_empty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(RedisBusApiBreaker.KEY)).thenReturn(null);

        assertThat(breaker.getBlockedUntil()).isEmpty();
    }

    @Test
    void TC_미래_차단시각_저장됨_차단_반환() {
        Instant until = NOW.plusSeconds(3600);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(RedisBusApiBreaker.KEY)).thenReturn(Long.toString(until.toEpochMilli()));

        assertThat(breaker.getBlockedUntil()).contains(until);
    }

    @Test
    void TC_과거_차단시각_empty() {
        Instant past = NOW.minusSeconds(10);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(RedisBusApiBreaker.KEY)).thenReturn(Long.toString(past.toEpochMilli()));

        assertThat(breaker.getBlockedUntil()).isEmpty();
    }

    @Test
    void TC_Redis_조회_장애_fail_closed_차단_반환() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(RedisBusApiBreaker.KEY)).thenThrow(new RuntimeException("redis down"));

        Optional<Instant> result = breaker.getBlockedUntil();

        // fail-closed: 짧은 차단 반환 (회복 가능하도록 NOW+FAIL_CLOSED_BLOCK)
        assertThat(result).contains(NOW.plus(RedisBusApiBreaker.FAIL_CLOSED_BLOCK));
    }

    @Test
    void TC_tripUntil_TTL과_함께_저장() {
        Instant until = NOW.plusSeconds(3600);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        breaker.tripUntil(until);

        verify(valueOps).set(eq(RedisBusApiBreaker.KEY),
                eq(Long.toString(until.toEpochMilli())),
                eq(Duration.ofSeconds(3600)));
    }

    @Test
    void TC_tripUntil_과거시각이면_무시() {
        breaker.tripUntil(NOW.minusSeconds(10));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void TC_tripUntil_Redis_장애_예외_안전() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doThrow(new RuntimeException("redis down")).when(valueOps).set(any(), any(), any(Duration.class));

        // 차단 기록 실패해도 예외 전파 안 함(호출자가 BusApiBlockedException으로 처리)
        breaker.tripUntil(NOW.plusSeconds(3600));
    }
}
