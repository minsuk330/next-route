package watoo.grd.nextroute.infrastructure.adapter.out.auth;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import watoo.grd.nextroute.application.auth.event.TokenGenerationChangedEvent;

import java.time.Duration;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 토큰 세대 캐시의 계약(#427).
 *
 * <p>조용히 망가지는 것이 둘이다. 읽는 쪽이 덮어쓰면 "옛 값을 읽어 둔 요청"이 로그아웃 직후 덮은 새 값을 지워 옛 토큰이
 * 1분간 되살아나고, Redis 장애가 인증 실패로 번지면 캐시 하나 때문에 전 사용자가 튕긴다.
 */
@DisplayName("토큰 세대 캐시")
class CachedTokenGenerationAdapterTest {

    private static final long USER = 7L;
    private static final String KEY = "auth:tokengen:7";

    private final UserTokenGenerationAdapter delegate = mock(UserTokenGenerationAdapter.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> redis = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private CachedTokenGenerationAdapter cache;

    @BeforeEach
    void setUp() {
        given(redis.opsForValue()).willReturn(ops);
        cache = new CachedTokenGenerationAdapter(delegate, redis, new SimpleMeterRegistry(), 60);
    }

    @Test
    @DisplayName("캐시에 있으면 DB 를 부르지 않는다")
    void hitSkipsDatabase() {
        given(ops.get(KEY)).willReturn("3");

        assertThat(cache.currentGeneration(USER)).hasValue(3);
        verifyNoInteractions(delegate);
    }

    @Test
    @DisplayName("탈퇴(없음)도 캐시된다 — 지운 계정의 토큰이 매번 DB 를 부르지 않는다")
    void absentIsCached() {
        given(ops.get(KEY)).willReturn("none");

        assertThat(cache.currentGeneration(USER)).isEmpty();
        verifyNoInteractions(delegate);
    }

    @Test
    @DisplayName("없으면 DB 에서 읽고 '비어 있을 때만' 채운다 — 덮어쓰지 않는다")
    void missFillsOnlyIfAbsent() {
        given(ops.get(KEY)).willReturn(null);
        given(delegate.currentGeneration(USER)).willReturn(OptionalInt.of(2));

        assertThat(cache.currentGeneration(USER)).hasValue(2);
        verify(ops).setIfAbsent(KEY, "2", Duration.ofSeconds(60));
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("세대가 바뀌면 커밋 뒤 새 값으로 덮어쓴다")
    void changeOverwrites() {
        given(delegate.currentGeneration(USER)).willReturn(OptionalInt.empty());

        cache.onChanged(new TokenGenerationChangedEvent(USER));

        verify(ops).set(KEY, "none", Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("Redis 가 죽으면 DB 로 내려간다 — 캐시 때문에 인증이 실패하지 않는다")
    void redisFailureFallsBack() {
        given(ops.get(KEY)).willThrow(new RedisConnectionFailureException("down"));
        given(delegate.currentGeneration(USER)).willReturn(OptionalInt.of(5));

        assertThat(cache.currentGeneration(USER)).hasValue(5);
    }

    @Test
    @DisplayName("알 수 없는 값은 믿지 않고 DB 로 간다")
    void garbageIsIgnored() {
        given(ops.get(KEY)).willReturn("x?");
        given(delegate.currentGeneration(USER)).willReturn(OptionalInt.of(1));

        assertThat(cache.currentGeneration(USER)).hasValue(1);
    }

    @Test
    @DisplayName("수명 0 이면 캐시를 쓰지 않는다(끄는 스위치)")
    void zeroTtlBypasses() {
        CachedTokenGenerationAdapter off = new CachedTokenGenerationAdapter(delegate, redis, new SimpleMeterRegistry(), 0);
        given(delegate.currentGeneration(USER)).willReturn(OptionalInt.of(4));

        assertThat(off.currentGeneration(USER)).hasValue(4);
        verifyNoInteractions(ops);
    }
}
