package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheReadPort;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheWritePort;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 실시간 버스 관측 캐시 Redis 어댑터. 키/TTL/직렬화·저장 정책의 단일 소유자.
 *
 * <p>검색 hot path(miss write)·공용 도착 조회(read)·collector(프라이밍 write)가 공유한다.
 * 키/직렬화가 여기 한 곳에 있어 소비자 간 드리프트를 구조적으로 막는다.
 *
 * <p>정책:
 * <ul>
 *   <li>TTL이 0 이하면 캐시 완전 우회(read miss, write no-op).</li>
 *   <li>빈 데이터는 저장하지 않는다(차단·에러로 인한 빈 결과를 TTL 동안 고착시키지 않기 위함).
 *       따라서 저장값은 항상 non-empty → read의 present는 곧 유효 hit.</li>
 *   <li>Redis 장애 시 read는 miss로, write는 warn 로그만 남기고 무시(graceful).</li>
 * </ul>
 */
@Slf4j
@Component
public class RedisBusRealtimeCacheAdapter implements BusRealtimeCacheReadPort, BusRealtimeCacheWritePort {

    static final String KEY_ARR = "bus:search:arr:";
    static final String KEY_POS = "bus:search:pos:";

    private static final TypeReference<List<BusArrivalInfo>> ARR_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<BusPositionInfo>> POS_TYPE = new TypeReference<>() {};

    private static final String TYPE_ARRIVAL = "arrival";
    private static final String TYPE_POSITION = "position";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final TransferArrivalProperties props;
    private final RouteSearchMetrics metrics;

    public RedisBusRealtimeCacheAdapter(RedisTemplate<String, String> redisTemplate,
                                        ObjectMapper objectMapper,
                                        TransferArrivalProperties props,
                                        RouteSearchMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.props = props;
        this.metrics = metrics;
    }

    // ── read ────────────────────────────────────────────────────────────────

    @Override
    public Optional<List<BusArrivalInfo>> getArrivals(String stopId, String routeId, int seq) {
        return read(arrKey(stopId, routeId, seq), ARR_TYPE, TYPE_ARRIVAL);
    }

    @Override
    public Optional<List<BusPositionInfo>> getPositions(String routeId) {
        return read(KEY_POS + routeId, POS_TYPE, TYPE_POSITION);
    }

    // ── write ───────────────────────────────────────────────────────────────

    @Override
    public void putArrivals(String stopId, String routeId, int seq, List<BusArrivalInfo> data) {
        write(arrKey(stopId, routeId, seq), data, TYPE_ARRIVAL);
    }

    @Override
    public void putPositions(String routeId, List<BusPositionInfo> data) {
        write(KEY_POS + routeId, data, TYPE_POSITION);
    }

    // ── 내부 ─────────────────────────────────────────────────────────────────

    private static String arrKey(String stopId, String routeId, int seq) {
        return KEY_ARR + stopId + ":" + routeId + ":" + seq;
    }

    private <T> Optional<List<T>> read(String key, TypeReference<List<T>> type, String metricType) {
        if (props.getCacheTtlSeconds() <= 0) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return Optional.empty();
            return Optional.ofNullable(objectMapper.readValue(json, type));
        } catch (Exception e) {
            // 장애는 miss 로 degrade 하되(호출자는 provider 경로로 계속), 원인은 지표로 남긴다.
            log.warn("[BusRealtimeCache] read failed [{}]: {}", key, e.getMessage());
            metrics.recordCacheError(metricType, "read");
            return Optional.empty();
        }
    }

    private <T> void write(String key, List<T> value, long ttlSeconds, String metricType) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("[BusRealtimeCache] write failed [{}]: {}", key, e.getMessage());
            metrics.recordCacheError(metricType, "write");
        }
    }

    private <T> void write(String key, List<T> value, String metricType) {
        long ttl = props.getCacheTtlSeconds();
        // TTL 비활성 또는 빈 데이터는 저장하지 않는다(빈 결과 고착 방지).
        if (ttl <= 0 || value == null || value.isEmpty()) {
            return;
        }
        write(key, value, ttl, metricType);
    }
}
