package watoo.grd.nextroute.infrastructure.adapter.out.cache.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.config.MlPredictorProperties;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPrediction;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPredictionStatus;
import watoo.grd.nextroute.application.route.port.out.MlPredictionCachePort;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ML 예측 캐시 Redis 어댑터. 키 접두사·TTL·직렬화의 단일 소유자.
 *
 * <p>정책은 버스 실시간 캐시({@code RedisBusRealtimeCacheAdapter})와 같다.
 * <ul>
 *   <li>TTL 이 0 이하면 캐시 완전 우회(read miss, write no-op)</li>
 *   <li>Redis 장애는 miss 로 내려앉히고 원인만 지표로 남긴다 — 예측 자체는 계속되어야 한다</li>
 * </ul>
 *
 * <p><b>키 접두사에 스키마 판을 박아 둔다.</b> feature 이름이 바뀌면 옛 값과 새 값이 같은 키를 쓰지
 * 않도록 {@code v1} 을 올린다.
 */
@Slf4j
@Component
public class RedisMlPredictionCacheAdapter implements MlPredictionCachePort {

    static final String KEY_PREFIX = "ml:pred:v1:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final MlPredictorProperties props;
    private final RouteSearchMetrics metrics;

    public RedisMlPredictionCacheAdapter(RedisTemplate<String, String> redisTemplate,
                                         ObjectMapper objectMapper,
                                         MlPredictorProperties props,
                                         RouteSearchMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.props = props;
        this.metrics = metrics;
    }

    @Override
    public Map<String, MlPrediction> findAll(List<String> cacheKeys) {
        if (props.getCacheTtlSeconds() <= 0 || cacheKeys == null || cacheKeys.isEmpty()) {
            return Map.of();
        }
        List<String> redisKeys = cacheKeys.stream().map(key -> KEY_PREFIX + key).toList();
        try {
            List<String> values = redisTemplate.opsForValue().multiGet(redisKeys);
            if (values == null) {
                return Map.of();
            }
            Map<String, MlPrediction> found = new HashMap<>();
            for (int i = 0; i < cacheKeys.size() && i < values.size(); i++) {
                String json = values.get(i);
                if (json == null) {
                    continue;
                }
                found.put(cacheKeys.get(i), decode(json));
            }
            return found;
        } catch (Exception e) {
            // 장애는 miss 로 degrade 한다(호출자는 serving 경로로 계속).
            log.warn("[MlPredictionCache] read failed [{}건]: {}", redisKeys.size(), e.getMessage());
            metrics.recordMlCacheError("read");
            return Map.of();
        }
    }

    @Override
    public void putAll(Map<String, MlPrediction> predictionsByCacheKey) {
        long ttl = props.getCacheTtlSeconds();
        if (ttl <= 0 || predictionsByCacheKey == null || predictionsByCacheKey.isEmpty()) {
            return;
        }
        try {
            Duration expiry = Duration.ofSeconds(ttl);
            for (Map.Entry<String, MlPrediction> entry : predictionsByCacheKey.entrySet()) {
                redisTemplate.opsForValue().set(
                        KEY_PREFIX + entry.getKey(), encode(entry.getValue()), expiry);
            }
        } catch (Exception e) {
            log.warn("[MlPredictionCache] write failed [{}건]: {}",
                    predictionsByCacheKey.size(), e.getMessage());
            metrics.recordMlCacheError("write");
        }
    }

    private String encode(MlPrediction prediction) throws Exception {
        return objectMapper.writeValueAsString(new CachedPrediction(
                prediction.status().name(), prediction.secondsToArrival(), prediction.modelVersion()));
    }

    /**
     * {@code requestId} 는 호출 측이 채운다. 여기서는 null 로 둔다 — 저장된 배치의 결합 키를 다음
     * 배치에 그대로 넘기면 예측이 엉뚱한 차량에 붙는다.
     */
    private MlPrediction decode(String json) throws Exception {
        CachedPrediction cached = objectMapper.readValue(json, CachedPrediction.class);
        return new MlPrediction(null, MlPredictionStatus.valueOf(cached.status()),
                cached.secondsToArrival(), cached.modelVersion());
    }

    /** 캐시 저장 형태. 배치 종속 필드({@code requestId})는 일부러 뺐다. */
    record CachedPrediction(String status, Double secondsToArrival, String modelVersion) {
    }
}
