package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;

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
        when(valueOps.get(RedisBusApiBreaker.blockedKey(BusApiQuotaPort.Endpoint.ARRIVAL))).thenReturn(null);

        assertThat(breaker.getBlockedUntil(BusApiQuotaPort.Endpoint.ARRIVAL)).isEmpty();
    }

    @Test
    void TC_미래_차단시각_저장됨_차단_반환() {
        Instant until = NOW.plusSeconds(3600);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(RedisBusApiBreaker.blockedKey(BusApiQuotaPort.Endpoint.POSITION)))
                .thenReturn(Long.toString(until.toEpochMilli()));

        assertThat(breaker.getBlockedUntil(BusApiQuotaPort.Endpoint.POSITION)).contains(until);
    }

    @Test
    void TC_과거_차단시각_empty() {
        Instant past = NOW.minusSeconds(10);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(RedisBusApiBreaker.blockedKey(BusApiQuotaPort.Endpoint.ARRIVAL)))
                .thenReturn(Long.toString(past.toEpochMilli()));

        assertThat(breaker.getBlockedUntil(BusApiQuotaPort.Endpoint.ARRIVAL)).isEmpty();
    }

    @Test
    void TC_Redis_조회_장애_fail_closed_차단_반환() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(RedisBusApiBreaker.blockedKey(BusApiQuotaPort.Endpoint.ARRIVAL)))
                .thenThrow(new RuntimeException("redis down"));

        Optional<Instant> result = breaker.getBlockedUntil(BusApiQuotaPort.Endpoint.ARRIVAL);

        // fail-closed: 짧은 차단 반환 (회복 가능하도록 NOW+FAIL_CLOSED_BLOCK)
        assertThat(result).contains(NOW.plus(RedisBusApiBreaker.FAIL_CLOSED_BLOCK));
    }

    @Test
    void TC_limit_count_3회부터_endpoint별_TTL과_함께_차단() {
        Instant until = NOW.plusSeconds(3600);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("bus:api:limit:2026-06-06:ARRIVAL")).thenReturn(3L);

        var decision = breaker.recordApiLimit(BusApiQuotaPort.Endpoint.ARRIVAL, until);

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.count()).isEqualTo(3);
        verify(valueOps).set(eq(RedisBusApiBreaker.blockedKey(BusApiQuotaPort.Endpoint.ARRIVAL)),
                eq(Long.toString(until.toEpochMilli())),
                eq(Duration.ofSeconds(3600)));
        verify(redisTemplate).expire(eq("bus:api:limit:2026-06-06:ARRIVAL"), eq(Duration.ofSeconds(3600)));
    }

    @Test
    void TC_limit_count_3회_전에는_차단하지_않고_count만_올린다() {
        Instant until = NOW.plusSeconds(3600);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("bus:api:limit:2026-06-06:POSITION")).thenReturn(2L);

        var decision = breaker.recordApiLimit(BusApiQuotaPort.Endpoint.POSITION, until);

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.count()).isEqualTo(2);
        verify(valueOps, never()).set(any(), any(), any(Duration.class));
        verify(redisTemplate).expire(eq("bus:api:limit:2026-06-06:POSITION"), eq(Duration.ofSeconds(3600)));
    }

    @Test
    void TC_recordApiLimit_과거시각이면_무시() {
        var decision = breaker.recordApiLimit(BusApiQuotaPort.Endpoint.ARRIVAL, NOW.minusSeconds(10));

        assertThat(decision.blocked()).isFalse();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void TC_recordApiLimit_Redis_장애시_호출자에게_fail_closed_결정_반환() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(any())).thenThrow(new RuntimeException("redis down"));

        var decision = breaker.recordApiLimit(BusApiQuotaPort.Endpoint.ARRIVAL, NOW.plusSeconds(3600));

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.count()).isEqualTo(RedisBusApiBreaker.LIMIT_THRESHOLD);
    }
}
