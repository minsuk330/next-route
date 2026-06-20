package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.config.BusApiQuotaProperties;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.common.config.ClockConfig;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 검색 quota Redis 카운터. 키 단위 일일(KST) INCR로 사용량을 추적, reserved 초과 시 차단한다.
 *
 * <p><b>fail-closed</b>: Redis 장애 시 false 반환(검색 호출 차단). 검색은 빈 결과로 graceful 처리.
 * 카운터 키는 다음 자정(KST)까지 TTL을 두어 일 단위로 리셋된다.
 */
@Slf4j
@Component
public class RedisBusApiQuota implements BusApiQuotaPort {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final String KEY_PREFIX = "bus:quota:search:";

    private final RedisTemplate<String, String> redisTemplate;
    private final BusApiQuotaProperties props;
    private final Clock clock;

    public RedisBusApiQuota(RedisTemplate<String, String> redisTemplate,
                            BusApiQuotaProperties props, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public boolean tryAcquireSearch(Endpoint endpoint) {
        int reserved = switch (endpoint) {
            case ARRIVAL -> props.getArrival();
            case POSITION -> props.getPosition();
        };
        if (reserved <= 0) {
            return false;
        }
        String key = key(endpoint);
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                log.error("[BusQuota] increment returned null — fail-closed [{}]", key);
                return false;
            }
            if (count == 1L) {
                // 첫 점유 시 다음 자정(KST)까지 TTL
                redisTemplate.expire(key, ttlToNextMidnight());
            }
            return count <= reserved;
        } catch (Exception e) {
            log.error("[BusQuota] Redis failure — fail-closed [{}]: {}", key, e.getMessage());
            return false;
        }
    }

    private String key(Endpoint endpoint) {
        String day = ZonedDateTime.now(clock).withZoneSameInstant(ClockConfig.KST)
                .toLocalDate().format(DAY);
        return KEY_PREFIX + endpoint.name().toLowerCase() + ":" + day;
    }

    private Duration ttlToNextMidnight() {
        ZonedDateTime nowKst = ZonedDateTime.now(clock).withZoneSameInstant(ClockConfig.KST);
        ZonedDateTime nextMidnight = nowKst.toLocalDate().plusDays(1).atStartOfDay(ClockConfig.KST);
        return Duration.between(nowKst, nextMidnight);
    }
}
