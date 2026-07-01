package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import watoo.grd.nextroute.application.bus.BusArrivalInfoFixtures;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisBusRealtimeCacheAdapterTest {

    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    ObjectMapper objectMapper = new ObjectMapper();
    TransferArrivalProperties props = new TransferArrivalProperties();
    RedisBusRealtimeCacheAdapter cache;

    @BeforeEach
    void setUp() {
        props.setCacheTtlSeconds(15);
        cache = new RedisBusRealtimeCacheAdapter(redisTemplate, objectMapper, props);
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
    void TC_position_get_hit() throws Exception {
        String key = RedisBusRealtimeCacheAdapter.KEY_POS + "R1";
        String json = objectMapper.writeValueAsString(List.of(pos("v1")));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(key)).thenReturn(json);

        Optional<List<BusPositionInfo>> hit = cache.getPositions("R1");

        assertThat(hit).isPresent();
        assertThat(hit.get()).hasSize(1);
        assertThat(hit.get().get(0).vehicleId()).isEqualTo("v1");
    }

    @Test
    void TC_position_get_miss() {
        String key = RedisBusRealtimeCacheAdapter.KEY_POS + "R1";
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(key)).thenReturn(null);

        assertThat(cache.getPositions("R1")).isEmpty();
    }

    @Test
    void TC_position_put_TTL로_저장() {
        String key = RedisBusRealtimeCacheAdapter.KEY_POS + "R1";
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        cache.putPositions("R1", List.of(pos("v1")));

        verify(valueOps).set(eq(key), anyString(), eq(Duration.ofSeconds(15)));
    }

    @Test
    void TC_arrival_put_키형태_stop_route_seq() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        cache.putArrivals("22000", "R1", 3,
                List.of(BusArrivalInfoFixtures.arrivalInfo("v1", "p1", "3분 후", "v2", "p2", "8분 후")));

        // 키 = bus:search:arr:{stopId}:{routeId}:{seq}
        verify(valueOps).set(
                eq(RedisBusRealtimeCacheAdapter.KEY_ARR + "22000:R1:3"),
                anyString(), eq(Duration.ofSeconds(15)));
    }

    @Test
    void TC_arrival_빈데이터_저장안함() {
        cache.putArrivals("22000", "R1", 3, List.of());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void TC_빈_데이터는_저장_안함() {
        cache.putPositions("R1", List.of());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void TC_ttl_0이면_write_우회() {
        props.setCacheTtlSeconds(0);
        cache.putPositions("R1", List.of(pos("v1")));
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void TC_ttl_0이면_read_우회() {
        props.setCacheTtlSeconds(0);
        assertThat(cache.getPositions("R1")).isEmpty();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void TC_Redis_조회_장애_graceful_miss() {
        String key = RedisBusRealtimeCacheAdapter.KEY_POS + "R1";
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(key)).thenThrow(new RuntimeException("redis down"));

        assertThat(cache.getPositions("R1")).isEmpty();
    }

    @Test
    void TC_Redis_저장_장애_graceful_예외삼킴() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        // 예외 전파 안 함
        cache.putPositions("R1", List.of(pos("v1")));
    }
}
