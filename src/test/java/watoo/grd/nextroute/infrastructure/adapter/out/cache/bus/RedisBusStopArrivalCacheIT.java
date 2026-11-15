package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import watoo.grd.nextroute.application.arrival.config.BusStopArrivalProperties;
import watoo.grd.nextroute.application.arrival.dto.BusStopArrivalResponse;

@DisplayName("정류장 단위 도착 캐시 Redis(#577)")
class RedisBusStopArrivalCacheIT {

    private LettuceConnectionFactory factory;
    private RedisTemplate<String, String> redis;
    private BusStopArrivalProperties props;
    private RedisBusStopArrivalCache a;
    private RedisBusStopArrivalCache b;
    private String stop;

    @BeforeEach
    void setUp() {
        factory = RedisTestSupport.factory();
        redis = RedisTestSupport.template(factory);
        props = new BusStopArrivalProperties();
        props.setCacheTtlSeconds(1);
        a = new RedisBusStopArrivalCache(redis, new ObjectMapper(), props);
        b = new RedisBusStopArrivalCache(redis, new ObjectMapper(), props);
        stop = "it-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        redis.keys(RedisBusStopArrivalCache.KEY_PREFIX + "*" + stop + "*").forEach(redis::delete);
        factory.destroy();
    }

    @Test
    @DisplayName("한 서버가 쓴 줄을 다른 서버가 그대로 읽고, null 칸과 빈 결과도 지킨다")
    void roundTripAcrossInstances() {
        BusStopArrivalResponse row = BusStopArrivalResponse.builder()
                .routeId("100100070").routeName("441").routeType(3).routeTypeSource("SEOUL").routeTypeName("간선")
                .arrivalMsg1("2분후").predictTime1(120).arrivalMsg2("운행종료").build();

        a.put("SEOUL", stop, List.of(row));
        a.put("GG", stop, List.of());

        assertThat(b.get("SEOUL", stop)).contains(List.of(row));
        assertThat(b.get("GG", stop)).as("빈 OK 도 hit").contains(List.of());
        assertThat(b.get("TAGO", stop)).isEmpty();
        assertThat(redis.getExpire(RedisBusStopArrivalCache.key("SEOUL", stop))).isBetween(0L, 1L);
    }

    @Test
    @DisplayName("TTL 뒤에는 miss, TTL 0 이면 쓰지도 읽지도 않는다")
    void expiresAndCanBeDisabled() throws Exception {
        a.put("SEOUL", stop, List.of());
        Thread.sleep(1300);
        assertThat(b.get("SEOUL", stop)).isEmpty();

        props.setCacheTtlSeconds(0);
        a.put("SEOUL", stop, List.of());
        assertThat(redis.hasKey(RedisBusStopArrivalCache.key("SEOUL", stop))).isFalse();
    }
}
