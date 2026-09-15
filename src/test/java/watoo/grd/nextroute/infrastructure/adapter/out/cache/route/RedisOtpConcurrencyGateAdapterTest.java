package watoo.grd.nextroute.infrastructure.adapter.out.cache.route;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import watoo.grd.nextroute.application.route.config.OtpConcurrencyProperties;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * gate 의 <b>fail 동작</b> 계약. 가장 중요한 것은 Redis 가 죽었을 때 무엇을 돌려주느냐다 —
 * 여기서 fail-open 하면 mem_limit 뒤에서 OTP 가 무제한 동시 호출을 받는다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OTP 동시성 gate — fail-closed·interrupt")
class RedisOtpConcurrencyGateAdapterTest {

    @Mock RedisTemplate<String, String> redisTemplate;

    private final OtpConcurrencyProperties props = new OtpConcurrencyProperties();
    private final Clock clock = Clock.systemUTC();

    private RedisOtpConcurrencyGateAdapter adapter() {
        return new RedisOtpConcurrencyGateAdapter(redisTemplate, props,
                new SimpleMeterRegistry(), clock);
    }

    @Test
    @DisplayName("Redis 장애 = fail-closed(empty). fail-open 아님")
    void redisFailureIsFailClosed() {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(), any(), any()))
                .thenThrow(new RuntimeException("redis down"));

        Optional<OtpPermit> permit = adapter().tryAcquire(RoutingDeadline.of(clock, 3000), "api");

        assertThat(permit).isEmpty();
    }

    @Test
    @DisplayName("예산이 이미 0 이면 포화로 즉시 empty — 대기하지 않는다")
    void expiredBudgetSaturatesImmediately() {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(), any(), any()))
                .thenReturn(0L);   // 정원 초과

        Optional<OtpPermit> permit = adapter().tryAcquire(RoutingDeadline.of(clock, 0), "worker");

        assertThat(permit).isEmpty();
    }

    @Test
    @DisplayName("대기 중 interrupt 되면 flag 복원 후 포화로 반환")
    void interruptRestoresFlagAndReturnsEmpty() {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(), any(), any()))
                .thenReturn(0L);   // 계속 포화 → sleep 진입
        Thread.currentThread().interrupt();   // sleep 이 즉시 InterruptedException

        Optional<OtpPermit> permit = adapter().tryAcquire(RoutingDeadline.of(clock, 5000), "api");

        assertThat(permit).isEmpty();
        assertThat(Thread.interrupted()).isTrue();   // flag 복원됨(그리고 여기서 clear)
    }

    @Test
    @DisplayName("release(null) 은 무해하다")
    void releaseNullIsSafe() {
        adapter().release(null);   // 예외 없음
    }
}
