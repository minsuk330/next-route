package watoo.grd.nextroute.infrastructure.adapter.out.cache.subway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.port.out.SubwayExpressSegmentTimePort;

import java.util.List;

/**
 * 급행 구간 실측 소요시간의 Redis 구현(#497).
 *
 * <p>{@code subway:xseg:{line}:{dir}:{from}:{to}} — list, 소요시간(초) 최신순 {@value #SAMPLES}개. TTL 7일(급행은 구간마다 하루
 * 수십 편이라 하루치로는 시간대가 치우친다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSubwayExpressSegmentTimeAdapter implements SubwayExpressSegmentTimePort {

    static final String PREFIX = "subway:xseg:";
    static final long TTL_SECONDS = 7 * 24 * 60 * 60;
    static final int SAMPLES = 9;

    /** ARGV: seconds, samples, ttl. */
    private static final RedisScript<Long> PUSH = new DefaultRedisScript<>("""
            redis.call('LPUSH', KEYS[1], ARGV[1])
            redis.call('LTRIM', KEYS[1], 0, tonumber(ARGV[2]) - 1)
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public void record(String lineId, String direction, String fromStationId, String toStationId, long seconds) {
        try {
            redisTemplate.execute(PUSH, List.of(key(lineId, direction, fromStationId, toStationId)),
                    String.valueOf(seconds), String.valueOf(SAMPLES), String.valueOf(TTL_SECONDS));
        } catch (Exception e) {
            log.debug("[ExpressSegmentTime] 기록 실패 {}:{}:{}→{}: {}", lineId, direction, fromStationId, toStationId,
                    e.getMessage());
        }
    }

    @Override
    public List<Long> recentSeconds(String lineId, String direction, String fromStationId, String toStationId) {
        try {
            List<String> values = redisTemplate.opsForList()
                    .range(key(lineId, direction, fromStationId, toStationId), 0, SAMPLES - 1);
            return values == null ? List.of() : values.stream().map(Long::parseLong).toList();
        } catch (Exception e) {
            log.debug("[ExpressSegmentTime] 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    static String key(String lineId, String direction, String fromStationId, String toStationId) {
        return PREFIX + lineId + ":" + direction + ":" + fromStationId + ":" + toStationId;
    }
}
