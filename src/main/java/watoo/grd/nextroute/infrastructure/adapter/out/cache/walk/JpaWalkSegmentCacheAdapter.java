package watoo.grd.nextroute.infrastructure.adapter.out.cache.walk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.route.dto.WalkCacheKey;
import watoo.grd.nextroute.application.route.dto.WalkSegment;
import watoo.grd.nextroute.application.route.port.out.WalkSegmentCachePort;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class JpaWalkSegmentCacheAdapter implements WalkSegmentCachePort {

    private final TmapWalkCacheRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public Optional<WalkSegment> get(WalkCacheKey key) {
        String cacheKey = key.asKey();
        Optional<TmapWalkCacheEntity> entity = repository.findByCacheKey(cacheKey);
        if (entity.isEmpty()) {
            return Optional.empty();
        }

        TmapWalkCacheEntity row = entity.get();
        OffsetDateTime now = OffsetDateTime.now();
        if (row.isExpired(now)) {
            log.debug("[WalkCache] STALE cacheKey={} expiresAt={}", cacheKey, row.getExpiresAt());
            return Optional.empty();
        }

        row.incrementHit();

        if (Boolean.TRUE.equals(row.getIsNegative())) {
            return Optional.of(WalkSegment.empty());
        }

        return Optional.of(deserialize(row.getWalkSegment()));
    }

    @Override
    @Transactional
    public void put(WalkCacheKey key, WalkSegment segment, Duration ttl) {
        upsert(key, serialize(segment),
                segment.totalDistance(), segment.totalTime(),
                false, ttl);
    }

    @Override
    @Transactional
    public void putNegative(WalkCacheKey key, Duration ttl) {
        upsert(key, serialize(WalkSegment.empty()),
                0, 0,
                true, ttl);
    }

    @Override
    @Transactional
    public int invalidateAll() {
        return repository.deleteAllRows();
    }

    @Override
    @Transactional
    public int invalidateByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return 0;
        }
        return repository.deleteByCacheKeyPrefix(prefix);
    }

    private void upsert(WalkCacheKey key, String walkSegmentJson,
                        Integer totalDistance, Integer totalTime,
                        boolean negative, Duration ttl) {
        String cacheKey = key.asKey();
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plus(ttl);

        repository.findByCacheKey(cacheKey).ifPresentOrElse(
                existing -> existing.overwrite(walkSegmentJson, totalDistance, totalTime, negative, now, expiresAt),
                () -> repository.save(TmapWalkCacheEntity.builder()
                        .cacheKey(cacheKey)
                        .startX(key.startX())
                        .startY(key.startY())
                        .endX(key.endX())
                        .endY(key.endY())
                        .searchOption((short) key.searchOption())
                        .walkSegment(walkSegmentJson)
                        .totalDistance(totalDistance)
                        .totalTime(totalTime)
                        .hitCount(0)
                        .isNegative(negative)
                        .fetchedAt(now)
                        .expiresAt(expiresAt)
                        .build())
        );
    }

    private String serialize(WalkSegment segment) {
        try {
            return objectMapper.writeValueAsString(segment);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize WalkSegment", e);
        }
    }

    private WalkSegment deserialize(String json) {
        try {
            return objectMapper.readValue(json, WalkSegment.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize WalkSegment", e);
        }
    }
}
