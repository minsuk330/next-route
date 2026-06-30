package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.port.out.BusApiBreakerPort;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.common.config.ClockConfig;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 서울 버스 API endpoint별 circuit breaker — Redis 백엔드.
 *
 * <p>ARRIVAL/POSITION별 차단 상태를 별도 키에 epoch-millis로 저장한다. error code 7 수신 시
 * endpoint별 일일 count를 올리고, count가 3회 이상이면 해당 endpoint만 차단한다.
 *
 * <p><b>fail-closed</b>: Redis 조회 실패 시 짧은 차단을 반환해 상태 불명 구간에서 provider quota를
 * 보호한다(회복 가능하도록 차단은 짧게). 영속·non-evictable 강화(별도 instance/DB)는 후속.
 */
@Slf4j
@Component
public class RedisBusApiBreaker implements BusApiBreakerPort {

    static final int LIMIT_THRESHOLD = 3;
    static final String BLOCKED_KEY_PREFIX = "bus:api:breaker:";
    static final String LIMIT_KEY_PREFIX = "bus:api:limit:";
    /** Redis 장애 시 fail-closed 차단 길이(회복 가능하도록 짧게). */
    static final Duration FAIL_CLOSED_BLOCK = Duration.ofSeconds(30);

    private final RedisTemplate<String, String> redisTemplate;
    private final Clock clock;

    public RedisBusApiBreaker(RedisTemplate<String, String> redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    @Override
    public Optional<Instant> getBlockedUntil(BusApiQuotaPort.Endpoint endpoint) {
        try {
            String raw = redisTemplate.opsForValue().get(blockedKey(endpoint));
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
            log.error("[BusBreaker] Redis read failed [{}] — fail-closed until {} : {}",
                    endpoint, until, e.getMessage());
            return Optional.of(until);
        }
    }

    @Override
    public LimitDecision recordApiLimit(BusApiQuotaPort.Endpoint endpoint, Instant until) {
        try {
            Duration ttl = Duration.between(Instant.now(clock), until);
            if (ttl.isNegative() || ttl.isZero()) {
                return new LimitDecision(endpoint, 0, false, until);
            }

            String limitKey = limitKey(endpoint);
            Long count = redisTemplate.opsForValue().increment(limitKey);
            int observed = count == null ? 1 : count.intValue();
            redisTemplate.expire(limitKey, ttl);

            if (observed >= LIMIT_THRESHOLD) {
                redisTemplate.opsForValue().set(blockedKey(endpoint), Long.toString(until.toEpochMilli()), ttl);
                log.warn("[BusBreaker] endpoint {} tripped until {} after {} limit responses",
                        endpoint, until, observed);
                return new LimitDecision(endpoint, observed, true, until);
            }

            log.warn("[BusBreaker] endpoint {} limit response count {}/{}",
                    endpoint, observed, LIMIT_THRESHOLD);
            return new LimitDecision(endpoint, observed, false, until);
        } catch (Exception e) {
            log.error("[BusBreaker] Redis write failed on recordApiLimit({}, {}): {}",
                    endpoint, until, e.getMessage());
            return new LimitDecision(endpoint, LIMIT_THRESHOLD, true, until);
        }
    }

    static String blockedKey(BusApiQuotaPort.Endpoint endpoint) {
        return BLOCKED_KEY_PREFIX + endpoint.name() + ":blocked-until";
    }

    String limitKey(BusApiQuotaPort.Endpoint endpoint) {
        LocalDate today = LocalDate.now(clock.withZone(ClockConfig.KST));
        return LIMIT_KEY_PREFIX + today + ":" + endpoint.name();
    }
}
