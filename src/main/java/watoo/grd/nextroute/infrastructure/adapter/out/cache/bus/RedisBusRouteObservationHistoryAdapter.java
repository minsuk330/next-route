package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.bus.port.out.BusRouteObservationHistoryPort;
import watoo.grd.nextroute.application.route.config.MlPredictorProperties;
import watoo.grd.nextroute.application.route.service.BusTimeParser;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 노선별 위치 관측 이력 Redis 어댑터. 노선당 ZSET 하나, score = 관측 시각(epoch seconds),
 * member = {@code vehicleId|sectionOrder|epoch}. 같은 관측은 member 가 같아 중복 저장되지 않는다.
 *
 * <p>쓸 때마다 보존 기간 밖 score 를 잘라내고 키 TTL 을 보존 기간으로 갱신하므로, 호출이
 * 끊긴 노선의 키는 저절로 사라진다. Redis 장애는 warn 로그만 남기고 삼킨다(이력은 best-effort).
 */
@Slf4j
@Component
public class RedisBusRouteObservationHistoryAdapter implements BusRouteObservationHistoryPort {

    static final String KEY = "bus:ml:hist:";
    private static final String SEP = "|";

    private final RedisTemplate<String, String> redisTemplate;
    private final MlPredictorProperties props;
    private final Clock clock;

    public RedisBusRouteObservationHistoryAdapter(RedisTemplate<String, String> redisTemplate,
                                                  MlPredictorProperties props,
                                                  Clock clock) {
        this.redisTemplate = redisTemplate;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public void append(String routeId, List<BusPositionInfo> positions) {
        if (!props.isHistoryEnabled() || routeId == null || positions == null || positions.isEmpty()) {
            return;
        }
        Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
        for (BusPositionInfo pos : positions) {
            if (pos.vehicleId() == null || pos.sectionOrder() == null) continue;
            Optional<Instant> at = BusTimeParser.parse(pos.dataTm());
            if (at.isEmpty()) continue;
            long epoch = at.get().getEpochSecond();
            String member = pos.vehicleId() + SEP + pos.sectionOrder() + SEP + epoch;
            tuples.add(ZSetOperations.TypedTuple.of(member, (double) epoch));
        }
        if (tuples.isEmpty()) {
            return;
        }
        String key = KEY + routeId;
        try {
            redisTemplate.opsForZSet().add(key, tuples);
            redisTemplate.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoff());
            redisTemplate.expire(key, retention());
        } catch (Exception e) {
            log.warn("[BusObservationHistory] append failed [{}]: {}", key, e.getMessage());
        }
    }

    @Override
    public List<Observation> recent(String routeId) {
        if (!props.isHistoryEnabled() || routeId == null) {
            return List.of();
        }
        String key = KEY + routeId;
        try {
            Set<String> members = redisTemplate.opsForZSet()
                    .rangeByScore(key, cutoff(), Double.POSITIVE_INFINITY);
            if (members == null || members.isEmpty()) {
                return List.of();
            }
            List<Observation> out = new ArrayList<>(members.size());
            for (String member : members) {
                Observation parsed = parse(member);
                if (parsed != null) out.add(parsed);
            }
            return out;
        } catch (Exception e) {
            log.warn("[BusObservationHistory] read failed [{}]: {}", key, e.getMessage());
            return List.of();
        }
    }

    private Duration retention() {
        return Duration.ofMinutes(Math.max(1, props.getHistoryMinutes()));
    }

    private double cutoff() {
        return (double) (Instant.now(clock).getEpochSecond() - retention().toSeconds());
    }

    static Observation parse(String member) {
        int a = member.indexOf(SEP);
        int b = member.lastIndexOf(SEP);
        if (a <= 0 || b <= a) return null;
        try {
            return new Observation(
                    member.substring(0, a),
                    Integer.parseInt(member.substring(a + 1, b)),
                    Long.parseLong(member.substring(b + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
