package watoo.grd.nextroute.infrastructure.adapter.out.cache.route;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.application.route.config.OtpConcurrencyProperties;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실 Redis 로 semaphore 의 핵심 계약을 고정한다 — <b>정원(N)·반납 후 재획득·lease 만료 자동 회수</b>.
 * mock 으론 ZREMRANGEBYSCORE/ZCARD/ZADD 의 원자·시각 의미를 못 잡는다.
 *
 * <p>정원·lease 는 기동 validator 를 피해 테스트 전용 {@link OtpConcurrencyProperties} 로 직접 만든
 * 어댑터에 넣는다(짧은 lease 로 만료를 관측하려면 5s 하한을 넘겨야 하기 때문).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("OTP 동시성 gate — 실 Redis 정원·lease")
class RedisOtpConcurrencyGateAdapterIT {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired RedisTemplate<String, String> redisTemplate;

    private final Clock clock = Clock.systemUTC();

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void clear() {
        redisTemplate.delete(RedisOtpConcurrencyGateAdapter.KEY);
    }

    private RedisOtpConcurrencyGateAdapter adapter(int permits, long leaseMs) {
        OtpConcurrencyProperties props = new OtpConcurrencyProperties();
        props.setPermits(permits);
        props.setLeaseMs(leaseMs);
        props.setRetryIntervalMs(10);
        return new RedisOtpConcurrencyGateAdapter(redisTemplate, props,
                new SimpleMeterRegistry(), clock);
    }

    @Test
    @DisplayName("정원 N 까지 발급하고 N+1 은 예산 안에 못 얻어 empty 다")
    void grantsUpToNThenSaturates() {
        RedisOtpConcurrencyGateAdapter gate = adapter(2, 8000);

        assertThat(gate.tryAcquire(RoutingDeadline.of(clock, 500), "api")).isPresent();
        assertThat(gate.tryAcquire(RoutingDeadline.of(clock, 500), "api")).isPresent();
        // 3번째는 200ms 예산 안에 자리가 안 나 포화(대기 후 empty).
        assertThat(gate.tryAcquire(RoutingDeadline.of(clock, 200), "worker")).isEmpty();
    }

    @Test
    @DisplayName("반납하면 다시 얻을 수 있다")
    void releaseAllowsReacquire() {
        RedisOtpConcurrencyGateAdapter gate = adapter(1, 8000);

        OtpPermit first = gate.tryAcquire(RoutingDeadline.of(clock, 500), "api").orElseThrow();
        assertThat(gate.tryAcquire(RoutingDeadline.of(clock, 200), "api")).isEmpty();  // 정원 1, 포화

        gate.release(first);

        assertThat(gate.tryAcquire(RoutingDeadline.of(clock, 500), "api")).isPresent();
    }

    @Test
    @DisplayName("lease 가 만료되면 반납 없이도 자동 회수된다(holder crash 대비)")
    void expiredLeaseIsReclaimed() throws InterruptedException {
        RedisOtpConcurrencyGateAdapter gate = adapter(1, 250);

        assertThat(gate.tryAcquire(RoutingDeadline.of(clock, 300), "api")).isPresent();
        // release 하지 않는다(=crash 흉내). lease 250ms 만료를 기다린다.
        Thread.sleep(400);

        // 다음 acquire 의 Lua 가 만료 토큰을 purge 하고 자리를 내준다.
        assertThat(gate.tryAcquire(RoutingDeadline.of(clock, 500), "api")).isPresent();
    }

    @Test
    @DisplayName("같은 permit 을 두 번 반납해도 안전하다(idempotent)")
    void releaseIsIdempotent() {
        RedisOtpConcurrencyGateAdapter gate = adapter(1, 8000);
        OtpPermit permit = gate.tryAcquire(RoutingDeadline.of(clock, 500), "api").orElseThrow();

        gate.release(permit);
        gate.release(permit);   // 두 번째도 예외 없음

        assertThat(gate.tryAcquire(RoutingDeadline.of(clock, 500), "api")).isPresent();
    }

    @Test
    @DisplayName("Redis 예외가 아니라 정상 포화면 재시도 후에도 empty 지만 자리가 나면 즉시 얻는다")
    void reacquireAfterConcurrentRelease() {
        RedisOtpConcurrencyGateAdapter gate = adapter(1, 8000);
        OtpPermit held = gate.tryAcquire(RoutingDeadline.of(clock, 500), "api").orElseThrow();

        // 대기 도중 다른 스레드가 반납하면 폴링이 그 자리를 잡는다.
        Thread releaser = new Thread(() -> {
            try { Thread.sleep(80); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            gate.release(held);
        });
        releaser.start();

        assertThat(gate.tryAcquire(RoutingDeadline.of(clock, 1000), "worker")).isPresent();
    }
}
