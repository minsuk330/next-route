package watoo.grd.nextroute.infrastructure.adapter.out.cache.journey;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.DetectionStamp;
import watoo.grd.nextroute.application.journey.port.out.DetectionStampPort;
import watoo.grd.nextroute.application.journey.reeval.ReevaluationTier;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 처음 본 시각 칸을 Redis 에 둔다(#446). 키 {@code journey:seen:{journeyId}}, 값 {@code 처음본시각ms:등급}.
 *
 * <p><b>왜 Redis 인가.</b> 측정 전용 값이라 DB 칸(마이그레이션·뜨거운 선점/완료 SQL 수정)을 들일 만큼 무겁지 않다. 쓰기는
 * 수집 한 번에 파이프라인 한 번, 읽기는 평가 한 번에 GETDEL 한 번이다. 모든 worker 인스턴스가 같은 Redis 를 보므로
 * 리더(수집기)가 남긴 표시를 어느 인스턴스의 평가가 소비해도 된다.
 *
 * <p><b>장애</b>는 삼킨다 — 표시가 없으면 지표가 평가 시작 시각(하한)으로 떨어질 뿐 판정·알림은 그대로다. 지표
 * {@code journey.notification.detection.stamp{result}} 로 빈 비율·오류를 본다.
 */
@Slf4j
@Component
public class RedisDetectionStampAdapter implements DetectionStampPort {

    static final String KEY_PREFIX = "journey:seen:";
    static final String METRIC = "journey.notification.detection.stamp";

    /** 되돌리기: 없거나 지금 칸이 더 늦으면 쓴다(더 이른 관측이 남는다). ARGV 1=값, 2=처음본시각ms, 3=수명초. */
    private static final DefaultRedisScript<Long> RESTORE_EARLIEST = new DefaultRedisScript<>("""
            local cur = redis.call('GET', KEYS[1])
            if cur then
              local t = tonumber(string.match(cur, '^(%d+):'))
              if t and t <= tonumber(ARGV[2]) then
                return 0
              end
            end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
            return 1
            """, Long.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final JourneyNotificationProperties properties;
    private final MeterRegistry meterRegistry;

    public RedisDetectionStampAdapter(RedisTemplate<String, String> redisTemplate,
                                      JourneyNotificationProperties properties,
                                      MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void stamp(Map<ReevaluationTier, ? extends Collection<Long>> journeysByTier, Instant observedAt) {
        if (!enabled() || observedAt == null || journeysByTier == null || journeysByTier.isEmpty()) {
            return;
        }
        Expiration ttl = Expiration.seconds(properties.getE2e().getStampTtlSeconds());
        int[] count = {0};
        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                journeysByTier.forEach((tier, ids) -> {
                    byte[] value = encode(observedAt, tier).getBytes(StandardCharsets.UTF_8);
                    for (Long id : ids) {
                        if (id == null) {
                            continue;
                        }
                        // 비어 있을 때만 — 아직 평가가 소비하지 않은 더 이른 관측을 덮지 않는다.
                        connection.stringCommands().set((KEY_PREFIX + id).getBytes(StandardCharsets.UTF_8), value,
                                ttl, RedisStringCommands.SetOption.ifAbsent());
                        count[0]++;
                    }
                });
                return null;
            });
            meterRegistry.counter(METRIC, "result", "stamped").increment(count[0]);
        } catch (RuntimeException e) {
            meterRegistry.counter(METRIC, "result", "error").increment();
            log.warn("[DetectionStamp] 표시 실패 — 이번 변화의 처음 본 시각은 평가 시작으로 떨어진다: {}", e.getMessage());
        }
    }

    @Override
    public Optional<DetectionStamp> consume(long journeyId) {
        if (!enabled()) {
            return Optional.empty();
        }
        try {
            String value = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + journeyId);
            DetectionStamp stamp = decode(value);
            meterRegistry.counter(METRIC, "result", stamp == null ? "empty" : "consumed").increment();
            return Optional.ofNullable(stamp);
        } catch (RuntimeException e) {
            meterRegistry.counter(METRIC, "result", "error").increment();
            log.debug("[DetectionStamp] 꺼내기 실패 journeyId={}: {}", journeyId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void restore(long journeyId, DetectionStamp stamp) {
        if (!enabled() || stamp == null || !DetectionStamp.FEED.equals(stamp.source())) {
            return;
        }
        try {
            redisTemplate.execute(RESTORE_EARLIEST, List.of(KEY_PREFIX + journeyId),
                    encode(stamp.firstSeenAt(), stamp.tier()),
                    String.valueOf(stamp.firstSeenAt().toEpochMilli()),
                    String.valueOf(properties.getE2e().getStampTtlSeconds()));
            meterRegistry.counter(METRIC, "result", "restored").increment();
        } catch (RuntimeException e) {
            meterRegistry.counter(METRIC, "result", "error").increment();
            log.debug("[DetectionStamp] 되돌리기 실패 journeyId={}: {}", journeyId, e.getMessage());
        }
    }

    private boolean enabled() {
        return properties.getE2e().isEnabled();
    }

    static String encode(Instant observedAt, ReevaluationTier tier) {
        return observedAt.toEpochMilli() + ":" + (tier == null ? ReevaluationTier.RELAXED : tier).name();
    }

    /** @return 비었거나 깨진 값이면 null */
    static DetectionStamp decode(String value) {
        if (value == null) {
            return null;
        }
        int colon = value.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        try {
            Instant at = Instant.ofEpochMilli(Long.parseLong(value.substring(0, colon)));
            ReevaluationTier tier = ReevaluationTier.valueOf(value.substring(colon + 1));
            return DetectionStamp.feed(at, tier);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
