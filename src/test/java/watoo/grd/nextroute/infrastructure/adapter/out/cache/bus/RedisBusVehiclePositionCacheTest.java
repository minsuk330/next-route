package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Outcome;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Provider;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Snapshot;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Vehicle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("단독 추적 버스 위치 캐시")
class RedisBusVehiclePositionCacheTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> redis = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisBusVehiclePositionCache cache =
            new RedisBusVehiclePositionCache(redis, new ObjectMapper().findAndRegisterModules());

    @Test
    @DisplayName("쓴 관측을 그대로 읽는다(키 = provider·노선, TTL 2분)")
    void roundTrip() {
        given(redis.opsForValue()).willReturn(values);
        Snapshot snapshot = Snapshot.ok(List.of(new Vehicle("V1", "서울1", 10, true)),
                Instant.parse("2026-10-07T03:00:00Z"));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);

        cache.put(Provider.GYEONGGI, "204000065", snapshot);

        verify(values).set(eq("track:bus:pos:GYEONGGI:204000065"), json.capture(), eq(Duration.ofMinutes(2)));
        given(values.get("track:bus:pos:GYEONGGI:204000065")).willReturn(json.getValue());
        assertThat(cache.get(Provider.GYEONGGI, "204000065")).contains(snapshot);
    }

    @Test
    @DisplayName("실패한 관측은 쓰지 않는다")
    void failedNotWritten() {
        given(redis.opsForValue()).willReturn(values);

        cache.put(Provider.TAGO, "R", Snapshot.failed(Outcome.ERROR, Instant.now()));

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }
}
