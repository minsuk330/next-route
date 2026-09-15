package watoo.grd.nextroute.infrastructure.adapter.out.cache.route;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.config.OtpConcurrencyProperties;
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * OTP 동시성 gate 의 Redis 구현. ZSET 하나(score=lease 만료시각ms, member=permit 토큰)로
 * 글로벌 semaphore 를 만든다. api/worker 어느 JVM 에서 불러도 같은 키를 보므로 합산 상한이 유지된다.
 *
 * <p><b>원자성</b>: acquire 는 Lua 한 번에 "만료 토큰 purge → 개수 확인 → 발급"을 묶는다
 * (ZREMRANGEBYSCORE + ZCARD + ZADD). 시각은 app Clock 이 아니라 <b>Redis 서버시간</b>
 * ({@code redis.call('TIME')})으로 계산해 api/worker 간 시계 skew 를 피한다.
 *
 * <p><b>fail-closed</b>: Redis 장애 시 acquire 는 empty(=포화와 동일). mem_limit 은 동시성 상한이
 * 아니므로 fail-open 하지 않는다. permit 상태는 공용 {@code allkeys-lru} Redis 에 두어 축출될 수
 * 있어 <b>best-effort 상한</b>이다(단일 Redis, 리스크 감수). hot key 라 실무상 축출은 드물고,
 * 하드 안전망은 OTP 컨테이너 {@code mem_limit} 이다.
 */
@Slf4j
@Component
public class RedisOtpConcurrencyGateAdapter implements OtpConcurrencyGate {

    static final String KEY = "otp:sem:permits";
    static final String ACQUIRE_METRIC = "otp.sem.acquire";
    static final String WAIT_METRIC = "otp.sem.wait";

    /**
     * 만료 토큰 purge + 정원 확인 + 발급을 원자로. now 는 Redis TIME(초·마이크로초) → ms.
     * ARGV[1]=limit, ARGV[2]=leaseMs, ARGV[3]=token. 정원 미달일 때만 ZADD 하므로 실패 시 토큰 잔류 없음.
     */
    private static final RedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local nowMs = t[1] * 1000 + math.floor(t[2] / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, nowMs)
            if redis.call('ZCARD', KEYS[1]) < tonumber(ARGV[1]) then
              redis.call('ZADD', KEYS[1], nowMs + tonumber(ARGV[2]), ARGV[3])
              return 1
            else
              return 0
            end
            """, Long.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final OtpConcurrencyProperties properties;
    private final MeterRegistry registry;
    private final Clock clock;

    public RedisOtpConcurrencyGateAdapter(RedisTemplate<String, String> redisTemplate,
                                          OtpConcurrencyProperties properties,
                                          MeterRegistry registry, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.registry = registry;
        this.clock = clock;
    }

    @Override
    public Optional<OtpPermit> tryAcquire(RoutingDeadline deadline, String role) {
        long startNanos = System.nanoTime();
        while (true) {
            String token = UUID.randomUUID().toString();
            Long acquired;
            try {
                acquired = redisTemplate.execute(ACQUIRE,
                        List.of(KEY),
                        String.valueOf(properties.getPermits()),
                        String.valueOf(properties.getLeaseMs()),
                        token);
            } catch (Exception e) {
                // fail-closed: 상태 불명 = 포화 처리. mem_limit 이 하드 안전망이라 fail-open 하지 않는다.
                log.error("[OtpSem] Redis 장애 — fail-closed(포화 처리) role={}: {}", role, e.getMessage());
                return finish(startNanos, role, "redis_error", Optional.empty());
            }

            if (acquired != null && acquired == 1L) {
                return finish(startNanos, role, "success", Optional.of(new OtpPermit(token)));
            }

            long remaining = deadline.remainingMillis(clock);
            if (remaining <= 0) {
                return finish(startNanos, role, "saturated", Optional.empty());
            }
            try {
                Thread.sleep(Math.min(properties.getRetryIntervalMs(), remaining));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();   // interrupt flag 복원 후 포화로 반환
                return finish(startNanos, role, "saturated", Optional.empty());
            }
        }
    }

    @Override
    public void release(OtpPermit permit) {
        if (permit == null) {
            return;
        }
        try {
            redisTemplate.opsForZSet().remove(KEY, permit.token());   // ZREM, idempotent
        } catch (Exception e) {
            // lease TTL(ZSET score 만료)이 뒤를 받친다. release 실패로 호출 경로를 죽이지 않는다.
            log.warn("[OtpSem] permit 반납 실패 — lease TTL 로 회수된다 token={}: {}",
                    permit.token(), e.getMessage());
        }
    }

    private Optional<OtpPermit> finish(long startNanos, String role, String result, Optional<OtpPermit> value) {
        registry.counter(ACQUIRE_METRIC, "role", role, "result", result).increment();
        Timer.builder(WAIT_METRIC).tag("role", role).tag("result", result)
                .register(registry).record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        return value;
    }
}
