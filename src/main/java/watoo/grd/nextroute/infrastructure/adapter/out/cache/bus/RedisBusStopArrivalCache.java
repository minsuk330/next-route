package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.arrival.config.BusStopArrivalProperties;
import watoo.grd.nextroute.application.arrival.dto.BusStopArrivalResponse;
import watoo.grd.nextroute.application.arrival.port.out.BusStopArrivalCachePort;

/**
 * 정류장 단위 도착 캐시(#577). 키 {@code bus:stop:arr:{SEOUL|GG|TAGO}:{공급자 쪽 정류장 키}}, TTL
 * {@code bus.stop-arrival.cache-ttl-seconds}. 성공한 빈 결과도 저장한다. Redis 장애는 miss·no-op(로그만).
 */
@Slf4j
@Component
public class RedisBusStopArrivalCache implements BusStopArrivalCachePort {

    static final String KEY_PREFIX = "bus:stop:arr:";
    private static final TypeReference<List<BusStopArrivalResponse>> ROWS = new TypeReference<>() {
    };

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final BusStopArrivalProperties props;

    public RedisBusStopArrivalCache(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper,
                                    BusStopArrivalProperties props) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @Override
    public Optional<List<BusStopArrivalResponse>> get(String source, String providerStopKey) {
        if (props.getCacheTtlSeconds() <= 0) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(key(source, providerStopKey));
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, ROWS));
        } catch (Exception e) {
            log.warn("[BusStopArrivalCache] read failed {}:{}: {}", source, providerStopKey, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String source, String providerStopKey, List<BusStopArrivalResponse> rows) {
        if (props.getCacheTtlSeconds() <= 0 || rows == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(source, providerStopKey), objectMapper.writeValueAsString(rows),
                    Duration.ofSeconds(props.getCacheTtlSeconds()));
        } catch (Exception e) {
            log.warn("[BusStopArrivalCache] write failed {}:{}: {}", source, providerStopKey, e.getMessage());
        }
    }

    static String key(String source, String providerStopKey) {
        return KEY_PREFIX + source + ":" + providerStopKey;
    }
}
