package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.port.out.BusApiBreakerPort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 서울 버스 API 공유 circuit breaker — Redis 백엔드.
 *
 * <p>차단 상태를 단일 키에 epoch-millis로 저장한다. collector·search 어댑터가 호출 전 조회하고
 * error code 7 수신 시 {@link #tripUntil(Instant)}로 함께 기록한다.
 *
 * <p><b>fail-closed</b>: Redis 조회 실패 시 짧은 차단을 반환해 상태 불명 구간에서 provider quota를
 * 보호한다(회복 가능하도록 차단은 짧게). 영속·non-evictable 강화(별도 instance/DB)는 후속.
 */
@Slf4j
@Component
public class RedisBusApiBreaker implements BusApiBreakerPort {

    static final String KEY = "bus:api:breaker:blocked-until";
    /** Redis 장애 시 fail-closed 차단 길이(회복 가능하도록 짧게). */
    static final Duration FAIL_CLOSED_BLOCK = Duration.ofSeconds(30);

    private final RedisTemplate<String, String> redisTemplate;
    private final Clock clock;

    public RedisBusApiBreaker(RedisTemplate<String, String> redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    @Override
    public Optional<Instant> getBlockedUntil() {
        try {
            String raw = redisTemplate.opsForValue().get(KEY);
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            Instant until = Instant.ofEpochMilli(Long.parseLong(raw.trim()));
            if (until.isAfter(Instant.now(clock))) {
                return Optional.of(until);
            }
            return Optional.empty();
        } catch (Exception e) {
            // fail-closed: 상태 불명 → 짧게 차단해 quota 보호
            Instant until = Instant.now(clock).plus(FAIL_CLOSED_BLOCK);
            log.error("[BusBreaker] Redis read failed — fail-closed until {} : {}", until, e.getMessage());
            return Optional.of(until);
        }
    }

    @Override
    public void tripUntil(Instant until) {
        try {
            Duration ttl = Duration.between(Instant.now(clock), until);
            if (ttl.isNegative() || ttl.isZero()) {
                return;
            }
            redisTemplate.opsForValue().set(KEY, Long.toString(until.toEpochMilli()), ttl);
            log.warn("[BusBreaker] tripped until {}", until);
        } catch (Exception e) {
            // 차단 결정 자체는 호출자가 BusApiBlockedException으로 처리하므로 기록 실패는 로그만.
            log.error("[BusBreaker] Redis write failed on tripUntil({}): {}", until, e.getMessage());
        }
    }
}
