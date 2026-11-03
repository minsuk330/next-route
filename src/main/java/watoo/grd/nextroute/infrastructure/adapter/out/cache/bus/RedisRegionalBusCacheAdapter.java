package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.arrival.config.RegionalBusSharedProperties;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.arrival.dto.TagoBusArrival;
import watoo.grd.nextroute.application.arrival.port.out.RegionalBusCachePort;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * {@link RegionalBusCachePort} 의 Redis 구현. 키 {@code bus:gg:arr:{stationId}:{routeId}}, {@code bus:tago:arr:{cityCode}:{nodeId}:{routeId}}.
 * 빈 목록도 저장한다(포트 규칙). Redis 오류는 read = miss, write = 무시.
 */
@Slf4j
@Component
public class RedisRegionalBusCacheAdapter implements RegionalBusCachePort {

    static final String KEY_GG = "bus:gg:arr:";
    static final String KEY_TAGO = "bus:tago:arr:";
    private static final TypeReference<List<GyeonggiBusArrival>> GG_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<TagoBusArrival>> TAGO_TYPE = new TypeReference<>() {};

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final RegionalBusSharedProperties props;

    public RedisRegionalBusCacheAdapter(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper,
                                        RegionalBusSharedProperties props) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @Override
    public Optional<List<GyeonggiBusArrival>> getGyeonggi(String stationId, String routeId) {
        return read(KEY_GG + stationId + ":" + routeId, GG_TYPE);
    }

    @Override
    public void putGyeonggi(String stationId, String routeId, List<GyeonggiBusArrival> data) {
        write(KEY_GG + stationId + ":" + routeId, data);
    }

    @Override
    public Optional<List<TagoBusArrival>> getTago(String cityCode, String nodeId, String routeId) {
        return read(KEY_TAGO + cityCode + ":" + nodeId + ":" + routeId, TAGO_TYPE);
    }

    @Override
    public void putTago(String cityCode, String nodeId, String routeId, List<TagoBusArrival> data) {
        write(KEY_TAGO + cityCode + ":" + nodeId + ":" + routeId, data);
    }

    private <T> Optional<List<T>> read(String key, TypeReference<List<T>> type) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            return json == null ? Optional.empty() : Optional.ofNullable(objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("[RegionalBusCache] read failed [{}]: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private <T> void write(String key, List<T> data) {
        if (data == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(data),
                    Duration.ofSeconds(props.getCacheTtlSeconds()));
        } catch (Exception e) {
            log.warn("[RegionalBusCache] write failed [{}]: {}", key, e.getMessage());
        }
    }
}
