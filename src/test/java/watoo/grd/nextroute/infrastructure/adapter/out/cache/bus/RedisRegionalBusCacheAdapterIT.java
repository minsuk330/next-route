package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import watoo.grd.nextroute.application.arrival.config.RegionalBusSharedProperties;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.arrival.dto.TagoBusArrival;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("지역 버스 공유 캐시 Redis")
class RedisRegionalBusCacheAdapterIT {

    private LettuceConnectionFactory factory;
    private RedisTemplate<String, String> redis;
    private RedisRegionalBusCacheAdapter a;
    private RedisRegionalBusCacheAdapter b;
    private String station;

    @BeforeEach
    void setUp() {
        factory = RedisTestSupport.factory();
        redis = RedisTestSupport.template(factory);
        RegionalBusSharedProperties props = new RegionalBusSharedProperties();
        props.setCacheTtlSeconds(1);
        a = new RedisRegionalBusCacheAdapter(redis, new ObjectMapper(), props);
        b = new RedisRegionalBusCacheAdapter(redis, new ObjectMapper(), props);
        station = "it-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        redis.keys("bus:gg:arr:" + station + "*").forEach(redis::delete);
        redis.keys("bus:tago:arr:*" + station + "*").forEach(redis::delete);
        factory.destroy();
    }

    @Test
    @DisplayName("한 어댑터가 쓴 값을 다른 어댑터가 읽는다(서버 공유), 빈 OK 도 저장된다")
    void sharedAcrossInstancesAndStoresEmpty() {
        GyeonggiBusArrival gg = new GyeonggiBusArrival("228000184", 1, 1, 1, 33, 29, 19, 1, 39, "PASS");
        a.putGyeonggi(station, "228000184", List.of(gg));
        a.putGyeonggi(station, "none", List.of());
        a.putTago("21", station, "BSB5200186000", List.of(new TagoBusArrival("BSB5200186000", 120, 3, "저상버스")));

        assertThat(b.getGyeonggi(station, "228000184")).contains(List.of(gg));
        assertThat(b.getGyeonggi(station, "none")).as("빈 OK 도 hit").contains(List.of());
        assertThat(b.getTago("21", station, "BSB5200186000")).isPresent();
        assertThat(b.getGyeonggi(station, "missing")).isEmpty();
    }

    @Test
    @DisplayName("TTL 뒤에는 miss")
    void expires() throws Exception {
        a.putGyeonggi(station, "r", List.of());
        assertThat(b.getGyeonggi(station, "r")).isPresent();
        TimeUnit.MILLISECONDS.sleep(1200);
        assertThat(b.getGyeonggi(station, "r")).isEmpty();
    }
}
