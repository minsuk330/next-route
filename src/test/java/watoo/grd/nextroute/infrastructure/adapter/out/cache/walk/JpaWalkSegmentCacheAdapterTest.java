package watoo.grd.nextroute.infrastructure.adapter.out.cache.walk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.dto.WalkCacheKey;
import watoo.grd.nextroute.application.route.dto.WalkSegment;
import watoo.grd.nextroute.application.route.dto.WalkStep;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JpaWalkSegmentCacheAdapterTest {

    @Mock
    TmapWalkCacheRepository repository;

    JpaWalkSegmentCacheAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JpaWalkSegmentCacheAdapter(repository, new ObjectMapper());
    }

    private WalkCacheKey key() {
        return new WalkCacheKey(127.0276, 37.4979, 127.0285, 37.4985, 0);
    }

    private WalkSegment sampleSegment() {
        return new WalkSegment(
                List.of(new CoordPoint(127.0276, 37.4979), new CoordPoint(127.0285, 37.4985)),
                320, 240,
                List.of(new WalkStep(0, "SP", 200, "이동", 127.0276, 37.4979))
        );
    }

    private TmapWalkCacheEntity validEntity(String cacheKey, String walkSegmentJson, boolean negative) {
        return TmapWalkCacheEntity.builder()
                .cacheKey(cacheKey)
                .startX(127.0276).startY(37.4979).endX(127.0285).endY(37.4985)
                .searchOption((short) 0)
                .walkSegment(walkSegmentJson)
                .totalDistance(320).totalTime(240)
                .hitCount(0)
                .isNegative(negative)
                .fetchedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(7))
                .build();
    }

    @Test
    void TC_put_후_get_HIT() throws Exception {
        WalkCacheKey k = key();
        WalkSegment segment = sampleSegment();

        // put: 새 row 저장
        when(repository.findByCacheKey(k.asKey())).thenReturn(Optional.empty());
        adapter.put(k, segment, Duration.ofDays(7));

        ArgumentCaptor<TmapWalkCacheEntity> captor = ArgumentCaptor.forClass(TmapWalkCacheEntity.class);
        verify(repository).save(captor.capture());
        TmapWalkCacheEntity saved = captor.getValue();
        assertThat(saved.getCacheKey()).isEqualTo(k.asKey());
        assertThat(saved.getIsNegative()).isFalse();

        // get: HIT
        when(repository.findByCacheKey(k.asKey())).thenReturn(Optional.of(
                validEntity(k.asKey(), saved.getWalkSegment(), false)));

        Optional<WalkSegment> hit = adapter.get(k);
        assertThat(hit).isPresent();
        assertThat(hit.get().polyline()).hasSize(2);
        assertThat(hit.get().totalDistance()).isEqualTo(320);
        assertThat(hit.get().steps()).hasSize(1);
    }

    @Test
    void TC_만료된_row는_MISS_반환() {
        WalkCacheKey k = key();
        TmapWalkCacheEntity expired = TmapWalkCacheEntity.builder()
                .cacheKey(k.asKey())
                .startX(127.0276).startY(37.4979).endX(127.0285).endY(37.4985)
                .searchOption((short) 0)
                .walkSegment("{}")
                .hitCount(0)
                .isNegative(false)
                .fetchedAt(OffsetDateTime.now().minusDays(10))
                .expiresAt(OffsetDateTime.now().minusDays(1))
                .build();
        when(repository.findByCacheKey(k.asKey())).thenReturn(Optional.of(expired));

        Optional<WalkSegment> result = adapter.get(k);
        assertThat(result).isEmpty();
    }

    @Test
    void TC_negative_cache_hit_시_empty_세그먼트_반환() throws Exception {
        WalkCacheKey k = key();
        String json = new ObjectMapper().writeValueAsString(WalkSegment.empty());
        TmapWalkCacheEntity negative = validEntity(k.asKey(), json, true);
        when(repository.findByCacheKey(k.asKey())).thenReturn(Optional.of(negative));

        Optional<WalkSegment> result = adapter.get(k);
        assertThat(result).isPresent();
        assertThat(result.get().isEmpty()).isTrue();
    }

    @Test
    void TC_putNegative_저장_시_isNegative_true() {
        WalkCacheKey k = key();
        when(repository.findByCacheKey(k.asKey())).thenReturn(Optional.empty());

        adapter.putNegative(k, Duration.ofDays(1));

        ArgumentCaptor<TmapWalkCacheEntity> captor = ArgumentCaptor.forClass(TmapWalkCacheEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getIsNegative()).isTrue();
    }

    @Test
    void TC_같은_키로_재저장_시_upsert로_overwrite() throws Exception {
        WalkCacheKey k = key();
        TmapWalkCacheEntity existing = validEntity(k.asKey(), "{}", false);
        when(repository.findByCacheKey(k.asKey())).thenReturn(Optional.of(existing));

        adapter.put(k, sampleSegment(), Duration.ofDays(7));

        // save가 호출되지 않고 기존 entity가 overwrite 됨
        verify(repository, never()).save(any());
        assertThat(existing.getIsNegative()).isFalse();
        assertThat(existing.getTotalDistance()).isEqualTo(320);
    }

    @Test
    void TC_hit_시_hitCount_증분() throws Exception {
        WalkCacheKey k = key();
        String json = new ObjectMapper().writeValueAsString(sampleSegment());
        TmapWalkCacheEntity row = validEntity(k.asKey(), json, false);
        when(repository.findByCacheKey(k.asKey())).thenReturn(Optional.of(row));

        adapter.get(k);

        assertThat(row.getHitCount()).isEqualTo(1);
    }

    @Test
    void TC_invalidateAll_repository_위임() {
        when(repository.deleteAllRows()).thenReturn(42);
        assertThat(adapter.invalidateAll()).isEqualTo(42);
    }

    @Test
    void TC_invalidateByPrefix_빈_prefix는_0_반환() {
        assertThat(adapter.invalidateByPrefix(null)).isZero();
        assertThat(adapter.invalidateByPrefix("")).isZero();
        verifyNoInteractions(repository);
    }

    @Test
    void TC_invalidateByPrefix_정상_prefix는_repository_위임() {
        when(repository.deleteByCacheKeyPrefix("coord:127.")).thenReturn(7);
        assertThat(adapter.invalidateByPrefix("coord:127.")).isEqualTo(7);
    }
}
