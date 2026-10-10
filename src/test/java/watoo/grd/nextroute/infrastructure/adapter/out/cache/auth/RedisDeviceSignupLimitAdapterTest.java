package watoo.grd.nextroute.infrastructure.adapter.out.cache.auth;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 기기 가입 한도: 고정창 카운터, fail-open, IPv6 /64 키. */
@ExtendWith(MockitoExtension.class)
class RedisDeviceSignupLimitAdapterTest {

    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    static final Instant NOW = Instant.parse("2026-10-10T04:30:00Z"); // KST 13:30
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    RedisDeviceSignupLimitAdapter limit;

    @BeforeEach
    void setUp() {
        limit = new RedisDeviceSignupLimitAdapter(redisTemplate, meterRegistry,
                Clock.fixed(NOW, ZoneOffset.UTC), 60);
    }

    @Test
    @DisplayName("첫 점유는 허용하고 1시간 TTL 을 건다 — 키는 출발지와 KST 시간 단위")
    void firstAcquire_setsTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);

        assertThat(limit.tryAcquire("203.0.113.7")).isTrue();

        verify(valueOps).increment("auth:device:signup:203.0.113.7:2026101013");
        verify(redisTemplate).expire(eq("auth:device:signup:203.0.113.7:2026101013"), eq(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("한도와 같으면 허용, TTL 은 다시 걸지 않는다")
    void atLimit_allowed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(60L);

        assertThat(limit.tryAcquire("203.0.113.7")).isTrue();
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("한도를 넘으면 거부하고 메트릭을 남긴다")
    void overLimit_rejected() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(61L);

        assertThat(limit.tryAcquire("203.0.113.7")).isFalse();
        assertThat(meterRegistry.counter(RedisDeviceSignupLimitAdapter.METRIC, "result", "limited").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Redis 가 죽으면 통과시킨다(fail-open) — 막으면 신규 사용자 전원이 앱을 못 쓴다")
    void redisFailure_failsOpen() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(limit.tryAcquire("203.0.113.7")).isTrue();
        assertThat(meterRegistry.counter(RedisDeviceSignupLimitAdapter.METRIC, "result", "redis_error").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("increment 가 null 이어도 통과시킨다")
    void nullIncrement_failsOpen() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(null);

        assertThat(limit.tryAcquire("203.0.113.7")).isTrue();
    }

    @Test
    @DisplayName("같은 /64 대역의 IPv6 는 같은 키로 센다 — 대역 안 주소 회전으로 한도를 피하지 못하게")
    void ipv6_samePrefix_sameKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(2L);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);

        limit.tryAcquire("2001:db8:1:2:aaaa::1");
        limit.tryAcquire("2001:db8:1:2:bbbb:cccc:dddd:eeee");
        limit.tryAcquire("2001:db8:1:3::1");

        verify(valueOps, org.mockito.Mockito.times(3)).increment(keys.capture());
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
        assertThat(keys.getAllValues().get(2)).isNotEqualTo(keys.getAllValues().get(0));
    }

    @Test
    @DisplayName("IP 키: IPv4 그대로, IPv6 는 앞 64비트, IPv4-mapped 는 IPv4, 비면 unknown")
    void ipKey() {
        assertThat(RedisDeviceSignupLimitAdapter.ipKey("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(RedisDeviceSignupLimitAdapter.ipKey("2001:db8:1:2::9")).isEqualTo("v6:20010db800010002");
        assertThat(RedisDeviceSignupLimitAdapter.ipKey("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(RedisDeviceSignupLimitAdapter.ipKey(null)).isEqualTo("unknown");
        assertThat(RedisDeviceSignupLimitAdapter.ipKey(" ")).isEqualTo("unknown");
    }
}
