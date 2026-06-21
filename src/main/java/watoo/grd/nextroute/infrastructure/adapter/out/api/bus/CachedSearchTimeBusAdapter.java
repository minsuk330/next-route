package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;

import java.time.Duration;
import java.util.List;

/**
 * 검색용 버스 API Redis 캐시 데코레이터.
 *
 * <p>같은 stopId/routeId 중복 검색이 provider 호출을 키우지 않도록 짧은 TTL로 응답을 캐시한다.
 * {@link SearchTimeBusAdapter}를 감싸 {@code @Primary}로 주입된다.
 *
 * <p>빈 결과는 캐시하지 않는다(차단·에러로 인한 빈 결과를 TTL 동안 고착시키지 않기 위함).
 * Redis 장애 시 캐시를 건너뛰고 delegate로 진행(graceful).
 */
@Slf4j
@Primary
@Component
public class CachedSearchTimeBusAdapter implements SearchTimeBusQueryPort {

    static final String KEY_ARR = "bus:search:arr:";
    static final String KEY_POS = "bus:search:pos:";

    private static final TypeReference<List<BusArrivalInfo>> ARR_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<BusPositionInfo>> POS_TYPE = new TypeReference<>() {};

    private final SearchTimeBusAdapter delegate;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final TransferArrivalProperties props;

    public CachedSearchTimeBusAdapter(SearchTimeBusAdapter delegate,
                                      RedisTemplate<String, String> redisTemplate,
                                      ObjectMapper objectMapper,
                                      TransferArrivalProperties props) {
        this.delegate = delegate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @Override
    public BusQueryResult<BusArrivalInfo> getArrInfoByStop(String stopId) {
        return cached(KEY_ARR + stopId, ARR_TYPE, () -> delegate.getArrInfoByStop(stopId));
    }

    @Override
    public BusQueryResult<BusPositionInfo> getBusPosByRtid(String busRouteId) {
        return cached(KEY_POS + busRouteId, POS_TYPE, () -> delegate.getBusPosByRtid(busRouteId));
    }

    private <T> BusQueryResult<T> cached(String key, TypeReference<List<T>> type,
                                         java.util.function.Supplier<BusQueryResult<T>> loader) {
        long ttl = props.getCacheTtlSeconds();
        if (ttl <= 0) {
            return loader.get();
        }
        List<T> hit = readCache(key, type);
        if (hit != null) {
            return BusQueryResult.cached(hit);   // provider 미호출 — cap·quota 미소모
        }
        BusQueryResult<T> fresh = loader.get();
        // 성공 + 비어있지 않을 때만 캐시(차단·제한·에러·빈 결과는 고착 방지)
        if (fresh.isOk() && !fresh.data().isEmpty()) {
            writeCache(key, fresh.data(), ttl);
        }
        return fresh;
    }

    private <T> List<T> readCache(String key, TypeReference<List<T>> type) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("[SearchBusCache] read failed [{}]: {}", key, e.getMessage());
            return null;
        }
    }

    private <T> void writeCache(String key, List<T> value, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("[SearchBusCache] write failed [{}]: {}", key, e.getMessage());
        }
    }
}
