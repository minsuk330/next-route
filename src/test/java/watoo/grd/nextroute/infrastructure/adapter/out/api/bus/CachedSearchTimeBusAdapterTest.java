package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CachedSearchTimeBusAdapterTest {

    @Mock SearchTimeBusAdapter delegate;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    ObjectMapper objectMapper = new ObjectMapper();
    TransferArrivalProperties props = new TransferArrivalProperties();
    CachedSearchTimeBusAdapter cache;

    @BeforeEach
    void setUp() {
        props.setCacheTtlSeconds(15);
        cache = new CachedSearchTimeBusAdapter(delegate, redisTemplate, objectMapper, props);
    }

    private BusPositionInfo pos(String vehId) {
        return new BusPositionInfo(
                vehId, 60, 3, 500.0, 10000.0, "0", "s1",
                "20260606130001", "AB1234", 0, 120, "prevStop",
                null, null, "0", "0", 1000.0, "nextStop",
                2, "turnStop", 127.01, 37.51, "1"
        );
    }

    @Test
    void TC_캐시_hit이면_delegate_미호출_cacheHit_true() throws Exception {
        String key = CachedSearchTimeBusAdapter.KEY_POS + "R1";
        String json = objectMapper.writeValueAsString(List.of(pos("v1")));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(key)).thenReturn(json);

        BusQueryResult<BusPositionInfo> result = cache.getBusPosByRtid("R1");

        assertThat(result.isOk()).isTrue();
        assertThat(result.cacheHit()).isTrue();   // provider 미호출 — cap·quota 미소모
        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).vehicleId()).isEqualTo("v1");
        verify(delegate, never()).getBusPosByRtid(any());
    }

    @Test
    void TC_캐시_miss이면_delegate_호출하고_저장_cacheHit_false() {
        String key = CachedSearchTimeBusAdapter.KEY_POS + "R1";
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(key)).thenReturn(null);
        when(delegate.getBusPosByRtid("R1")).thenReturn(BusQueryResult.ok(List.of(pos("v1"))));

        BusQueryResult<BusPositionInfo> result = cache.getBusPosByRtid("R1");

        assertThat(result.data()).hasSize(1);
        assertThat(result.cacheHit()).isFalse();
        verify(valueOps).set(eq(key), anyString(), eq(Duration.ofSeconds(15)));
    }

    @Test
    void TC_빈_결과는_캐시하지_않음() {
        String key = CachedSearchTimeBusAdapter.KEY_POS + "R1";
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(key)).thenReturn(null);
        when(delegate.getBusPosByRtid("R1")).thenReturn(BusQueryResult.ok(List.of()));

        BusQueryResult<BusPositionInfo> result = cache.getBusPosByRtid("R1");

        assertThat(result.data()).isEmpty();
        verify(valueOps, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void TC_차단_결과는_캐시하지_않음() {
        String key = CachedSearchTimeBusAdapter.KEY_POS + "R1";
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(key)).thenReturn(null);
        when(delegate.getBusPosByRtid("R1")).thenReturn(BusQueryResult.blocked());

        BusQueryResult<BusPositionInfo> result = cache.getBusPosByRtid("R1");

        // BLOCKED outcome 전파, 캐시 안 함(고착 방지)
        assertThat(result.isOk()).isFalse();
        verify(valueOps, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void TC_ttl_0이면_캐시_우회_delegate_직접() {
        props.setCacheTtlSeconds(0);
        when(delegate.getBusPosByRtid("R1")).thenReturn(BusQueryResult.ok(List.of(pos("v1"))));

        BusQueryResult<BusPositionInfo> result = cache.getBusPosByRtid("R1");

        assertThat(result.data()).hasSize(1);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void TC_Redis_조회_장애_graceful_delegate로_진행() {
        String key = CachedSearchTimeBusAdapter.KEY_POS + "R1";
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(key)).thenThrow(new RuntimeException("redis down"));
        when(delegate.getBusPosByRtid("R1")).thenReturn(BusQueryResult.ok(List.of(pos("v1"))));

        BusQueryResult<BusPositionInfo> result = cache.getBusPosByRtid("R1");

        assertThat(result.data()).hasSize(1);
    }
}
