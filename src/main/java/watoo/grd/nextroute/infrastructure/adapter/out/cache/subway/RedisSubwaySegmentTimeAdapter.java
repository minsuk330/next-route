package watoo.grd.nextroute.infrastructure.adapter.out.cache.subway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.port.out.SubwaySegmentTimePort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 구간 실측 소요시간의 Redis 구현.
 *
 * <ul>
 *   <li>{@code subway:seg:dep:{line}:{dir}:{station}} — hash, 열차 번호 → 전역 출발을 처음 본 epoch 초. TTL 30분</li>
 *   <li>{@code subway:seg:dur:{line}:{dir}:{station}} — list, 소요시간(초) 최신순 3개. TTL 3시간</li>
 * </ul>
 *
 * <p>출발·도착 기록은 각각 Lua 한 문장이다. 수집기 하나가 쓰지만, "읽고 지우고 더한다"를 쪼개면 재시작 중첩 때 같은
 * 표본이 두 번 들어갈 수 있다.
 *
 * <p>hash TTL 은 키 전체에 걸리고 다른 열차가 쓸 때마다 연장되므로, 도착을 놓친 열차의 출발 기록은 남을 수 있다.
 * 그래서 15분 넘은 출발 기록은 새 출발이 덮고, 900초 넘는 소요시간은 표본으로 받지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSubwaySegmentTimeAdapter implements SubwaySegmentTimePort {

    static final String DEP_PREFIX = "subway:seg:dep:";
    static final String DUR_PREFIX = "subway:seg:dur:";
    static final long DEP_TTL_SECONDS = 30 * 60;
    static final long DUR_TTL_SECONDS = 3 * 60 * 60;
    static final long STALE_DEPARTURE_SECONDS = 15 * 60;
    static final long MAX_SEGMENT_SECONDS = 900;
    static final int SAMPLES = 3;

    /** ARGV: train, epochSec, staleSec, ttl. 없거나 옛 값이면 쓴다. */
    private static final RedisScript<Long> DEPARTED = new DefaultRedisScript<>("""
            local prev = redis.call('HGET', KEYS[1], ARGV[1])
            local now = tonumber(ARGV[2])
            if prev and now - tonumber(prev) <= tonumber(ARGV[3]) and now >= tonumber(prev) then
              return 0
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return 1
            """, Long.class);

    /** ARGV: train, epochSec, maxSec, samples, durTtl. 출발 기록이 있으면 표본을 더한다. */
    private static final RedisScript<Long> ARRIVED = new DefaultRedisScript<>("""
            local dep = redis.call('HGET', KEYS[1], ARGV[1])
            if not dep then
              return -1
            end
            redis.call('HDEL', KEYS[1], ARGV[1])
            local d = tonumber(ARGV[2]) - tonumber(dep)
            if d <= 0 or d > tonumber(ARGV[3]) then
              return -2
            end
            redis.call('LPUSH', KEYS[2], d)
            redis.call('LTRIM', KEYS[2], 0, tonumber(ARGV[4]) - 1)
            redis.call('EXPIRE', KEYS[2], ARGV[5])
            return d
            """, Long.class);

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public void recordDeparted(String lineId, String direction, String stationId, String trainNo, Instant at) {
        try {
            redisTemplate.execute(DEPARTED, List.of(depKey(lineId, direction, stationId)),
                    trainNo, String.valueOf(at.getEpochSecond()), String.valueOf(STALE_DEPARTURE_SECONDS),
                    String.valueOf(DEP_TTL_SECONDS));
        } catch (Exception e) {
            log.debug("[SegmentTime] 출발 기록 실패 {}:{}:{} {}: {}", lineId, direction, stationId, trainNo, e.getMessage());
        }
    }

    @Override
    public void recordArrived(String lineId, String direction, String stationId, String trainNo, Instant at) {
        try {
            redisTemplate.execute(ARRIVED,
                    List.of(depKey(lineId, direction, stationId), durKey(lineId, direction, stationId)),
                    trainNo, String.valueOf(at.getEpochSecond()), String.valueOf(MAX_SEGMENT_SECONDS),
                    String.valueOf(SAMPLES), String.valueOf(DUR_TTL_SECONDS));
        } catch (Exception e) {
            log.debug("[SegmentTime] 도착 기록 실패 {}:{}:{} {}: {}", lineId, direction, stationId, trainNo, e.getMessage());
        }
    }

    @Override
    public Optional<Instant> departedAt(String lineId, String direction, String stationId, String trainNo) {
        try {
            Object value = redisTemplate.opsForHash().get(depKey(lineId, direction, stationId), trainNo);
            return value == null ? Optional.empty() : Optional.of(Instant.ofEpochSecond(Long.parseLong(value.toString())));
        } catch (Exception e) {
            log.debug("[SegmentTime] 출발 조회 실패: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<Long> recentSeconds(String lineId, String direction, String stationId) {
        try {
            List<String> values = redisTemplate.opsForList().range(durKey(lineId, direction, stationId), 0, SAMPLES - 1);
            return values == null ? List.of() : values.stream().map(Long::parseLong).toList();
        } catch (Exception e) {
            log.debug("[SegmentTime] 표본 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    static String depKey(String lineId, String direction, String stationId) {
        return DEP_PREFIX + lineId + ":" + direction + ":" + stationId;
    }

    static String durKey(String lineId, String direction, String stationId) {
        return DUR_PREFIX + lineId + ":" + direction + ":" + stationId;
    }
}
