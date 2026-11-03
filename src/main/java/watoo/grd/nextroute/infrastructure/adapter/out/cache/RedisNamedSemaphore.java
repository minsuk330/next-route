package watoo.grd.nextroute.infrastructure.adapter.out.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 이름 붙은 서버 공유 세마포어(bus-multi-instance PR-4). {@code RedisOtpConcurrencyGateAdapter} 와 같은 Lua(ZSET, Redis TIME,
 * lease)를 쓰되 OTP 쪽 키·지표·빈은 건드리지 않는 형제다(플랜 검토 #11).
 *
 * <p>키 {@code sem:{name}:permits}. fail-closed — Redis 오류면 자리 없음. lease 가 지나면 자리는 저절로 회수된다(release 를
 * 못 해도 새는 것은 lease 만큼).
 */
@Slf4j
@Component
public class RedisNamedSemaphore {

    static final String KEY_PREFIX = "sem:";

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

    public RedisNamedSemaphore(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 자리를 하나 얻는다. {@code waitMs} 동안 {@code retryMs} 간격으로 다시 시도한다.
     *
     * @return 얻으면 토큰. 포화·Redis 오류·인터럽트면 empty.
     */
    public Optional<String> tryAcquire(String name, int limit, long leaseMs, long waitMs, long retryMs) {
        long deadline = System.nanoTime() + waitMs * 1_000_000L;
        String key = key(name);
        while (true) {
            String token = UUID.randomUUID().toString();
            Long acquired;
            try {
                acquired = redisTemplate.execute(ACQUIRE, List.of(key),
                        String.valueOf(limit), String.valueOf(leaseMs), token);
            } catch (Exception e) {
                log.warn("[NamedSem] Redis 오류 — 자리 없음으로 처리 name={}: {}", name, e.getMessage());
                return Optional.empty();
            }
            if (acquired != null && acquired == 1L) {
                return Optional.of(token);
            }
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                return Optional.empty();
            }
            try {
                Thread.sleep(Math.min(retryMs, remainingMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    public void release(String name, String token) {
        if (token == null) {
            return;
        }
        try {
            redisTemplate.opsForZSet().remove(key(name), token);
        } catch (Exception e) {
            log.warn("[NamedSem] 반납 실패 — lease 로 회수된다 name={}: {}", name, e.getMessage());
        }
    }

    static String key(String name) {
        return KEY_PREFIX + name + ":permits";
    }
}
