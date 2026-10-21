package watoo.grd.nextroute.infrastructure.adapter.out.cache.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.config.RouteSnapshotProperties;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 검색 결과 스냅샷의 Redis 구현. 키는 {@code route:snapshot:{searchSessionId}}, 값은 보강까지 끝난
 * {@link RouteSearchResult} 를 소유자·만료시각과 함께 감싼 JSON 이다. 만료는 Redis TTL 이 처리한다.
 *
 * <p><b>어떤 예외도 밖으로 던지지 않는다.</b> 이 어댑터는 {@code /api/route/search} 응답 경로에서
 * 호출되므로 Redis 장애가 검색을 실패시키면 안 된다(PLAN §4.3). 저장 실패는 {@code null},
 * 조회 실패는 {@code Optional.empty()} 로 흡수하고 {@code log.warn} 만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRouteSearchSnapshotAdapter implements RouteSearchSnapshotPort {

    private static final String KEY_PREFIX = "route:snapshot:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final RouteSnapshotProperties properties;

    @Override
    public String save(Long ownerUserId, RouteSearchResult result, boolean excludeExpress, Instant expiresAt) {
        String searchSessionId = UUID.randomUUID().toString();
        try {
            String json = objectMapper.writeValueAsString(
                    new StoredSnapshot(ownerUserId, expiresAt.toEpochMilli(), result, excludeExpress));
            redisTemplate.opsForValue().set(
                    key(searchSessionId), json, Duration.ofSeconds(properties.getTtlSeconds()));
            return searchSessionId;
        } catch (Exception e) {
            log.warn("[RouteSnapshot] 스냅샷 저장 실패 — 검색 응답은 유지한다: {}", e.toString());
            return null;
        }
    }

    @Override
    public Optional<StoredPath> find(String searchSessionId, String pathKey) {
        if (searchSessionId == null || pathKey == null) {
            return Optional.empty();
        }
        int index;
        try {
            index = Integer.parseInt(pathKey);
        } catch (NumberFormatException e) {
            log.warn("[RouteSnapshot] pathKey 가 숫자가 아니다: {}", pathKey);
            return Optional.empty();
        }
        if (index < 0) {
            return Optional.empty();
        }

        String json;
        try {
            json = redisTemplate.opsForValue().get(key(searchSessionId));
        } catch (Exception e) {
            log.warn("[RouteSnapshot] 스냅샷 조회 실패: {}", e.toString());
            return Optional.empty();
        }
        if (json == null) {
            return Optional.empty();   // 만료됐거나 없는 세션
        }

        StoredSnapshot snapshot;
        try {
            snapshot = objectMapper.readValue(json, StoredSnapshot.class);
        } catch (Exception e) {
            log.warn("[RouteSnapshot] 스냅샷 역직렬화 실패: {}", e.toString());
            return Optional.empty();
        }

        List<PathResult> paths = snapshot.result() == null ? null : snapshot.result().paths();
        if (paths == null || index >= paths.size()) {
            return Optional.empty();
        }
        return Optional.of(new StoredPath(
                snapshot.ownerUserId(),
                paths.get(index),
                Instant.ofEpochMilli(snapshot.expiresAtEpochMilli()),
                Boolean.TRUE.equals(snapshot.excludeExpress())));
    }

    private String key(String searchSessionId) {
        return KEY_PREFIX + searchSessionId;
    }

    /**
     * Redis 에 실제로 담기는 형태. 만료시각은 {@code Instant} 직렬화 설정에 의존하지 않도록
     * epoch milli 로 고정한다.
     *
     * <p>{@code excludeExpress}(subway-express S5)는 끝 칸·박싱 타입이다 — 배포 전에 저장된 스냅샷(칸 없음)은 null 로
     * 읽혀 false 가 된다. 구버전 reader 는 모르는 칸을 무시한다.
     */
    record StoredSnapshot(Long ownerUserId, long expiresAtEpochMilli, RouteSearchResult result,
                          Boolean excludeExpress) {
    }
}
