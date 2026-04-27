package watoo.grd.nextroute.infrastructure.adapter.out.cache.subway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeSnapshot;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeStatus;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSubwayRealtimeCacheAdapter implements SubwayRealtimeCachePort {

    private static final String KEY_SNAPSHOT = "realtime:subway:snapshot";
    private static final String KEY_STATUS   = "realtime:subway:status";
    private static final String KEY_BOOT     = "realtime:subway:boot-time";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void saveSnapshot(SubwayRealtimeSnapshot snapshot, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            redisTemplate.opsForValue().set(KEY_SNAPSHOT, json, Duration.ofSeconds(ttlSeconds));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize subway snapshot", e);
        }
    }

    @Override
    public Optional<SubwayRealtimeSnapshot> readSnapshot() {
        String json = redisTemplate.opsForValue().get(KEY_SNAPSHOT);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, SubwayRealtimeSnapshot.class));
        } catch (JsonProcessingException e) {
            log.warn("[RedisCache] Failed to parse subway snapshot: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void saveStatus(SubwayRealtimeStatus status) {
        redisTemplate.opsForValue().set(KEY_STATUS, status.name());
    }

    @Override
    public Optional<SubwayRealtimeStatus> readStatus() {
        String val = redisTemplate.opsForValue().get(KEY_STATUS);
        if (val == null) return Optional.empty();
        try {
            return Optional.of(SubwayRealtimeStatus.valueOf(val));
        } catch (IllegalArgumentException e) {
            log.warn("[RedisCache] Unknown status value in Redis: {}", val);
            return Optional.empty();
        }
    }

    @Override
    public void saveBootTime(String bootTimeIso) {
        redisTemplate.opsForValue().set(KEY_BOOT, bootTimeIso);
    }
}
