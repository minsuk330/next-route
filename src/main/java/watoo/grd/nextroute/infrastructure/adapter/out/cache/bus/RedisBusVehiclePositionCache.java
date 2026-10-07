package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort;
import watoo.grd.nextroute.application.bus.port.out.BusVehiclePositionCachePort;

import java.time.Duration;
import java.util.Optional;

/**
 * 단독 추적 버스 노선 위치 캐시(#265). 키 {@code track:bus:pos:{provider}:{routeId}}, TTL 2분 — 관찰 간격(최대 60초)의 두
 * 배라 정상 관찰 중에는 끊기지 않고, 관찰이 멈추면 낡은 위치가 오래 남지 않는다. Redis 장애는 miss·무시.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisBusVehiclePositionCache implements BusVehiclePositionCachePort {

    static final String KEY = "track:bus:pos:";
    static final Duration TTL = Duration.ofMinutes(2);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void put(BusPositionPort.Provider provider, String routeId, BusPositionPort.Snapshot snapshot) {
        if (snapshot == null || !snapshot.isOk()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(provider, routeId), objectMapper.writeValueAsString(snapshot), TTL);
        } catch (Exception e) {
            log.warn("[TrackBusPosCache] write failed {}:{}: {}", provider, routeId, e.getMessage());
        }
    }

    @Override
    public Optional<BusPositionPort.Snapshot> get(BusPositionPort.Provider provider, String routeId) {
        try {
            String json = redisTemplate.opsForValue().get(key(provider, routeId));
            return json == null ? Optional.empty()
                    : Optional.of(objectMapper.readValue(json, BusPositionPort.Snapshot.class));
        } catch (Exception e) {
            log.warn("[TrackBusPosCache] read failed {}:{}: {}", provider, routeId, e.getMessage());
            return Optional.empty();
        }
    }

    private static String key(BusPositionPort.Provider provider, String routeId) {
        return KEY + provider + ":" + routeId;
    }
}
