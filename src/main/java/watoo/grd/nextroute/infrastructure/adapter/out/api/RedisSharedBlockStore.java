package watoo.grd.nextroute.infrastructure.adapter.out.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.common.config.ClockConfig;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * 경기·TAGO 버스 도착 차단기의 Redis 공유 저장소. 플래그 {@code regional-bus.shared.enabled} 가 켜질 때만 빈이 된다 —
 * 지하철 어댑터 등 다른 이름은 맡지 않는다(hot path 에 GET 을 붙이지 않게, 플랜 검토 #8).
 *
 * <p>키 {@code api:block:{name}}, 값 = 차단한 KST 날짜, TTL = KST 자정까지. 기존 JVM 차단기는 시스템 시간대 자정을 쓰지만
 * 공유 판은 KST 로 못 박는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "regional-bus.shared", name = "enabled", havingValue = "true")
public class RedisSharedBlockStore implements SharedBlockStore {

    static final String KEY_PREFIX = "api:block:";
    static final Set<String> NAMES = Set.of("gyeonggi-bus-arrival", "tago-bus-arrival");

    private final RedisTemplate<String, String> redisTemplate;
    private final Clock clock;

    public RedisSharedBlockStore(RedisTemplate<String, String> redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    @Override
    public boolean handles(String apiName) {
        return NAMES.contains(apiName);
    }

    @Override
    public Optional<Boolean> isBlocked(String apiName) {
        try {
            String blockedOn = redisTemplate.opsForValue().get(KEY_PREFIX + apiName);
            return Optional.of(blockedOn != null && blockedOn.equals(today().toString()));
        } catch (Exception e) {
            log.warn("[SharedBlock] 읽기 실패 — JVM 차단기로 내려간다 name={}: {}", apiName, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void block(String apiName) {
        try {
            ZonedDateTime nowKst = ZonedDateTime.now(clock).withZoneSameInstant(ClockConfig.KST);
            Duration ttl = Duration.between(nowKst, nowKst.toLocalDate().plusDays(1).atStartOfDay(ClockConfig.KST));
            redisTemplate.opsForValue().set(KEY_PREFIX + apiName, today().toString(), ttl);
        } catch (Exception e) {
            log.warn("[SharedBlock] 쓰기 실패 — 이 서버만 차단된다 name={}: {}", apiName, e.getMessage());
        }
    }

    private LocalDate today() {
        return ZonedDateTime.now(clock).withZoneSameInstant(ClockConfig.KST).toLocalDate();
    }
}
