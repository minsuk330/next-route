package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import watoo.grd.nextroute.application.bus.config.BusApiQuotaProperties;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort.Endpoint;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisBusApiQuotaTest {

    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    static final Instant NOW = Instant.parse("2026-06-06T04:00:00Z"); // KST 13:00
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    BusApiQuotaProperties props = new BusApiQuotaProperties();
    RedisBusApiQuota quota;

    @BeforeEach
    void setUp() {
        props.setArrival(5000);
        props.setPosition(3000);
        quota = new RedisBusApiQuota(redisTemplate, props, clock);
    }

    @Test
    void TC_첫_점유_TTL_설정_허용() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);

        assertThat(quota.tryAcquireSearch(Endpoint.ARRIVAL)).isTrue();
        // 첫 INCR(count==1)에 TTL 설정
        verify(redisTemplate).expire(anyString(), any(Duration.class));
    }

    @Test
    void TC_reserved_이내_허용_TTL_미설정() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(5000L); // == reserved

        assertThat(quota.tryAcquireSearch(Endpoint.ARRIVAL)).isTrue();
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void TC_reserved_초과_차단() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(5001L); // > reserved

        assertThat(quota.tryAcquireSearch(Endpoint.ARRIVAL)).isFalse();
    }

    @Test
    void TC_엔드포인트별_reserved_분리() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(3001L);

        // position reserved=3000 → 3001 초과
        assertThat(quota.tryAcquireSearch(Endpoint.POSITION)).isFalse();
    }

    @Test
    void TC_reserved_0이면_차단_increment_안함() {
        props.setArrival(0);

        assertThat(quota.tryAcquireSearch(Endpoint.ARRIVAL)).isFalse();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void TC_increment_null_fail_closed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(null);

        assertThat(quota.tryAcquireSearch(Endpoint.ARRIVAL)).isFalse();
    }

    @Test
    void TC_Redis_장애_fail_closed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(quota.tryAcquireSearch(Endpoint.ARRIVAL)).isFalse();
    }
}
