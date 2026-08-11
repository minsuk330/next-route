package watoo.grd.nextroute.infrastructure.adapter.out.cache.journey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * cooldown 키의 계약.
 *
 * <p>가장 중요한 것은 <b>실패했을 때 무엇을 돌려주느냐</b>다. 여기서 통과시키면 ③이 켜진 뒤
 * Redis 장애가 곧 ODsay 요금이 된다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("재탐색 cooldown")
class RedisRerouteCooldownAdapterTest {

    private static final Long JOURNEY_ID = 42L;
    private static final String KEY = "journey:reroute:cooldown:42:1:0";

    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    private final JourneyRerouteProperties properties = new JourneyRerouteProperties();
    private RedisRerouteCooldownAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new RedisRerouteCooldownAdapter(redisTemplate, properties);
    }

    @Test
    @DisplayName("처음 찾으면 통과한다")
    void firstAttemptIsAdmitted() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(KEY), eq("1"), eq(Duration.ofMinutes(2)))).thenReturn(true);

        assertThat(adapter.tryAdmit(JOURNEY_ID, 1, 0)).isTrue();
    }

    @Test
    @DisplayName("cooldown 안에 다시 오면 거절한다")
    void secondAttemptWithinCooldownIsRejected() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(KEY), eq("1"), eq(Duration.ofMinutes(2)))).thenReturn(false);

        assertThat(adapter.tryAdmit(JOURNEY_ID, 1, 0)).isFalse();
    }

    @Test
    @DisplayName("설정한 TTL 을 그대로 심는다 — 심기와 만료가 한 문장이다")
    void ttlComesFromProperties() {
        properties.getTrigger().setCooldown(Duration.ofSeconds(45));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(KEY), eq("1"), eq(Duration.ofSeconds(45)))).thenReturn(true);

        assertThat(adapter.tryAdmit(JOURNEY_ID, 1, 0)).isTrue();
        // 두 문장으로 나누면 그 사이에 죽었을 때 만료 없는 키가 남아 그 환승이 영영 막힌다.
        verify(valueOps).setIfAbsent(eq(KEY), eq("1"), eq(Duration.ofSeconds(45)));
    }

    @Test
    @DisplayName("Redis 가 죽으면 통과시키지 않는다")
    void redisFailureIsFailClosed() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        assertThat(adapter.tryAdmit(JOURNEY_ID, 1, 0))
                .as("여기서 열어 두면 ③이 켜진 뒤 캐시 장애가 곧 외부 검색 요금이 된다")
                .isFalse();
    }

    @Test
    @DisplayName("null 응답도 통과가 아니다")
    void nullReplyIsNotAdmission() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(KEY), eq("1"), eq(Duration.ofMinutes(2)))).thenReturn(null);

        assertThat(adapter.tryAdmit(JOURNEY_ID, 1, 0)).isFalse();
    }

    @Test
    @DisplayName("cooldown 이 0 이면 Redis 를 건드리지 않고 통과시킨다")
    void zeroCooldownSkipsRedis() {
        properties.getTrigger().setCooldown(Duration.ZERO);

        assertThat(adapter.tryAdmit(JOURNEY_ID, 1, 0)).isTrue();
        // Redis 는 TTL 0 을 거부한다. 원시 발생률만 보고 싶을 때 쓰는 설정이다.
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("키에 개정본이 들어간다 — 경로가 바뀌면 앞선 cooldown 이 막지 않는다")
    void keyIncludesRouteRevision() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("journey:reroute:cooldown:42:2:0"), eq("1"),
                eq(Duration.ofMinutes(2)))).thenReturn(true);

        assertThat(adapter.tryAdmit(JOURNEY_ID, 2, 0)).isTrue();
    }
}
