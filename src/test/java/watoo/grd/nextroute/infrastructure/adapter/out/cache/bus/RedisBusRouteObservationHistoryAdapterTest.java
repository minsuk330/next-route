package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.bus.port.out.BusRouteObservationHistoryPort.Observation;
import watoo.grd.nextroute.application.route.config.MlPredictorProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisBusRouteObservationHistoryAdapterTest {

    // 2026-06-06 13:00:00 KST = 04:00:00 UTC
    private static final Instant NOW = Instant.parse("2026-06-06T04:00:00Z");
    private static final String KEY = RedisBusRouteObservationHistoryAdapter.KEY + "R1";

    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ZSetOperations<String, String> zset;

    MlPredictorProperties props = new MlPredictorProperties();
    RedisBusRouteObservationHistoryAdapter adapter;

    @BeforeEach
    void setUp() {
        props.setHistoryMinutes(100);
        adapter = new RedisBusRouteObservationHistoryAdapter(
                redisTemplate, props, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
    }

    private BusPositionInfo pos(String vehId, Integer sectionOrder, String dataTm) {
        return new BusPositionInfo(
                vehId, 60, sectionOrder, 500.0, 10000.0, "0", "s1",
                dataTm, "AB1234", 0, 120, "prevStop",
                null, null, "0", "0", 1000.0, "nextStop",
                2, "turnStop", 127.01, 37.51, "1"
        );
    }

    @Test
    void TC_append는_관측을_score가_관측시각인_member로_저장하고_보존기간_밖을_잘라낸다() {
        when(redisTemplate.opsForZSet()).thenReturn(zset);

        adapter.append("R1", List.of(
                pos("v1", 3, "20260606125900"),   // 12:59:00 KST = epoch 1780718340
                pos("v2", null, "20260606125900"), // sectionOrder 없음 → 제외
                pos("v3", 5, "bad")));             // dataTm 파싱 실패 → 제외

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<ZSetOperations.TypedTuple<String>>> captor = ArgumentCaptor.forClass(Set.class);
        verify(zset).add(eq(KEY), captor.capture());
        long epoch = Instant.parse("2026-06-06T03:59:00Z").getEpochSecond();
        assertThat(captor.getValue()).hasSize(1);
        ZSetOperations.TypedTuple<String> tuple = captor.getValue().iterator().next();
        assertThat(tuple.getValue()).isEqualTo("v1|3|" + epoch);
        assertThat(tuple.getScore()).isEqualTo((double) epoch);

        double cutoff = NOW.getEpochSecond() - Duration.ofMinutes(100).toSeconds();
        verify(zset).removeRangeByScore(KEY, Double.NEGATIVE_INFINITY, cutoff);
        verify(redisTemplate).expire(KEY, Duration.ofMinutes(100));
    }

    @Test
    void TC_recent는_보존기간_안의_member를_관측으로_되돌린다() {
        when(redisTemplate.opsForZSet()).thenReturn(zset);
        Set<String> members = new LinkedHashSet<>(List.of("v1|3|1780718340", "v2|7|1780718400", "garbage"));
        double cutoff = NOW.getEpochSecond() - Duration.ofMinutes(100).toSeconds();
        when(zset.rangeByScore(KEY, cutoff, Double.POSITIVE_INFINITY)).thenReturn(members);

        List<Observation> recent = adapter.recent("R1");

        assertThat(recent).containsExactly(
                new Observation("v1", 3, 1780718340L),
                new Observation("v2", 7, 1780718400L));
    }

    @Test
    void TC_기능_off면_저장도_조회도_하지_않는다() {
        props.setHistoryEnabled(false);

        adapter.append("R1", List.of(pos("v1", 3, "20260606125900")));
        List<Observation> recent = adapter.recent("R1");

        assertThat(recent).isEmpty();
        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    void TC_Redis_장애는_삼키고_빈_이력을_돌려준다() {
        when(redisTemplate.opsForZSet()).thenReturn(zset);
        when(zset.add(anyString(), any(Set.class))).thenThrow(new RuntimeException("down"));
        when(zset.rangeByScore(anyString(), anyDouble(), anyDouble())).thenThrow(new RuntimeException("down"));

        adapter.append("R1", List.of(pos("v1", 3, "20260606125900")));

        assertThat(adapter.recent("R1")).isEmpty();
    }
}
