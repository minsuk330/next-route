package watoo.grd.nextroute.infrastructure.adapter.out.cache.subway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.config.SubwayStationArrivalProperties;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.application.subway.observability.SubwayRealtimeMetrics;
import watoo.grd.nextroute.application.subway.port.out.SubwayStationArrivalCachePort;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 역별 지하철 실시간 응답 캐시 Redis 어댑터. 키·TTL·직렬화의 단일 소유자.
 *
 * <p>정책은 버스 실시간 캐시와 같다 — TTL 0 이하면 완전 우회, 빈 응답은 저장하지 않음(장애로 인한
 * 빈 목록을 TTL 동안 고착시키지 않는다), Redis 장애는 miss 로 내려앉힘.
 */
@Slf4j
@Component
public class RedisSubwayStationArrivalCacheAdapter implements SubwayStationArrivalCachePort {

    static final String KEY_PREFIX = "subway:arr:station:";

    private static final TypeReference<List<SubwayArrivalInfo>> TYPE = new TypeReference<>() {
    };

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final SubwayStationArrivalProperties props;
    private final SubwayRealtimeMetrics metrics;

    public RedisSubwayStationArrivalCacheAdapter(RedisTemplate<String, String> redisTemplate,
                                                 ObjectMapper objectMapper,
                                                 SubwayStationArrivalProperties props,
                                                 SubwayRealtimeMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.props = props;
        this.metrics = metrics;
    }

    @Override
    public Optional<List<SubwayArrivalInfo>> find(String stationName) {
        if (props.getCacheTtlSeconds() <= 0 || stationName == null) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + stationName);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(objectMapper.readValue(json, TYPE));
        } catch (Exception e) {
            log.warn("[SubwayStationArrivalCache] read failed [{}]: {}", stationName, e.getMessage());
            metrics.recordCacheError("read");
            return Optional.empty();
        }
    }

    @Override
    public void put(String stationName, List<SubwayArrivalInfo> arrivals) {
        long ttl = props.getCacheTtlSeconds();
        if (ttl <= 0 || stationName == null || arrivals == null || arrivals.isEmpty()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + stationName,
                    objectMapper.writeValueAsString(arrivals), Duration.ofSeconds(ttl));
        } catch (Exception e) {
            log.warn("[SubwayStationArrivalCache] write failed [{}]: {}", stationName, e.getMessage());
            metrics.recordCacheError("write");
        }
    }
}
