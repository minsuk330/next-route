package watoo.grd.nextroute.infrastructure.adapter.out.cache.journey;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.port.out.RemainingRouteCachePort;
import watoo.grd.nextroute.application.route.dto.PathResult;

import java.util.List;
import java.util.Optional;

/**
 * 남은 구간 경로 캐시의 Redis 구현. 키는 {@code journey:reroute:remaining:{stopId}:{destX}:{destY}},
 * 값은 경로 목록 JSON 이다.
 *
 * <p><b>읽기도 쓰기도 예외를 삼킨다(fail-open).</b> Redis 가 죽으면 캐시가 없는 것처럼 동작해
 * 검색이 그대로 나간다 — 요금은 늘지만 기능은 산다. 같은 패키지의
 * {@code RedisRerouteCooldownAdapter} 는 정반대(fail-closed)인데, 그쪽은 열리면 요금이 새기 때문이다.
 * 둘을 같은 규칙으로 다루면 하나는 반드시 틀린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRemainingRouteCacheAdapter implements RemainingRouteCachePort {

    static final String KEY_PREFIX = "journey:reroute:remaining:";

    private static final TypeReference<List<PathResult>> PATHS = new TypeReference<>() {
    };

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final JourneyRerouteProperties properties;

    @Override
    public Optional<List<PathResult>> find(String stopId, double destX, double destY) {
        String key = key(stopId, destX, destY);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            List<PathResult> paths = objectMapper.readValue(json, PATHS);
            // 빈 목록을 캐시에 넣지 않으므로 여기서 나오는 빈 값은 깨진 값이다. 없는 것으로 본다.
            return paths.isEmpty() ? Optional.empty() : Optional.of(paths);
        } catch (Exception e) {
            log.warn("[JourneyReroute] 남은 구간 캐시 조회 실패 — 없는 것처럼 진행한다 [{}]: {}",
                    key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String stopId, double destX, double destY, List<PathResult> paths) {
        if (paths == null || paths.isEmpty()) {
            // 빈 답을 10분간 들고 있으면 그 구간이 10분 내내 빈손이 된다.
            return;
        }
        String key = key(stopId, destX, destY);
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(paths),
                    properties.getRemainingPath().getCacheTtl());
        } catch (Exception e) {
            log.warn("[JourneyReroute] 남은 구간 캐시 저장 실패 — 검색 결과는 그대로 쓴다 [{}]: {}",
                    key, e.getMessage());
        }
    }

    private String key(String stopId, double destX, double destY) {
        return KEY_PREFIX + RemainingRouteCachePort.key(stopId, destX, destY);
    }
}
