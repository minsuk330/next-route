package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import watoo.grd.nextroute.application.bus.BusArrivalInfoFixtures;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.route.port.out.CrossInstanceFlightPort;
import watoo.grd.nextroute.application.route.port.out.CrossInstanceFlightPort.Lead;
import watoo.grd.nextroute.application.route.port.out.CrossInstanceFlightPort.Peek;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.Outcome;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 서버 사이 single-flight 의 Redis 계약. REDIS_HOST·REDIS_PORT 의 일회용 Redis. */
@DisplayName("서버 사이 single-flight Redis")
class RedisCrossInstanceFlightIT {

    private static final String TYPE = "arrival";

    private LettuceConnectionFactory factory;
    private RedisTemplate<String, String> redis;
    private RedisCrossInstanceFlight a;
    private RedisCrossInstanceFlight b;
    private String key;

    @BeforeEach
    void setUp() {
        String host = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        factory.afterPropertiesSet();
        redis = new RedisTemplate<>();
        redis.setConnectionFactory(factory);
        redis.setKeySerializer(new StringRedisSerializer());
        redis.setValueSerializer(new StringRedisSerializer());
        redis.afterPropertiesSet();
        a = new RedisCrossInstanceFlight(redis, new ObjectMapper());
        b = new RedisCrossInstanceFlight(redis, new ObjectMapper());
        key = "it:" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        redis.keys("bus:flight:*" + key + "*").forEach(redis::delete);
        factory.destroy();
    }

    @Test
    @DisplayName("둘째 시도는 리더가 아니고 첫 리더의 토큰을 돌려받는다")
    void secondLeadReturnsHolderToken() {
        Lead first = a.tryLead(TYPE, key, Duration.ofSeconds(5));
        Lead second = b.tryLead(TYPE, key, Duration.ofSeconds(5));

        assertThat(first.mine()).isTrue();
        assertThat(second.mine()).isFalse();
        assertThat(second.token()).isEqualTo(first.token());
        assertThat(a.currentHolder(TYPE, key)).contains(first.token());
    }

    @Test
    @DisplayName("결과 키는 토큰별이고, 리더가 남긴 outcome 과 data 가 그대로 읽힌다")
    void resultIsPerToken() {
        Lead first = a.tryLead(TYPE, key, Duration.ofSeconds(5));
        BusArrivalInfo info = BusArrivalInfoFixtures.arrivalWithSectionTime(60, null);

        a.finish(TYPE, key, first.token(), Outcome.OK, List.of(info), Duration.ofSeconds(2));

        Optional<Peek<BusArrivalInfo>> peek = b.peekResult(TYPE, key, first.token(), BusArrivalInfo.class);
        assertThat(peek).isPresent();
        assertThat(peek.get().outcome()).isEqualTo(Outcome.OK);
        assertThat(peek.get().data()).containsExactly(info);
        assertThat(b.peekResult(TYPE, key, "other-token", BusArrivalInfo.class)).as("다른 토큰의 결과는 없다").isEmpty();
        assertThat(a.currentHolder(TYPE, key)).as("finish 가 잠금을 풀었다").isEmpty();
    }

    @Test
    @DisplayName("빈 OK 와 LIMITED 도 결과로 전해진다")
    void emptyAndLimitedResults() {
        Lead first = a.tryLead(TYPE, key, Duration.ofSeconds(5));
        a.finish(TYPE, key, first.token(), Outcome.OK, List.of(), Duration.ofSeconds(2));
        Optional<Peek<BusArrivalInfo>> empty = b.peekResult(TYPE, key, first.token(), BusArrivalInfo.class);
        assertThat(empty).isPresent();
        assertThat(empty.get().data()).isEmpty();

        Lead second = a.tryLead(TYPE, key, Duration.ofSeconds(5));
        assertThat(second.mine()).isTrue();
        a.finish(TYPE, key, second.token(), Outcome.LIMITED, List.of(), Duration.ofSeconds(2));
        Optional<Peek<BusArrivalInfo>> limited = b.peekResult(TYPE, key, second.token(), BusArrivalInfo.class);
        assertThat(limited).isPresent();
        assertThat(limited.get().outcome()).isEqualTo(Outcome.LIMITED);
    }

    @Test
    @DisplayName("토큰이 다른 finish 는 남의 잠금을 지우지 않는다")
    void finishWithWrongTokenKeepsLock() {
        Lead first = a.tryLead(TYPE, key, Duration.ofSeconds(5));

        b.finish(TYPE, key, "stale-token", Outcome.ERROR, List.of(), Duration.ofSeconds(2));

        assertThat(a.currentHolder(TYPE, key)).contains(first.token());
        assertThat(redis.opsForValue().get(RedisCrossInstanceFlight.lockKey(TYPE, key))).isEqualTo(first.token());
    }

    @Test
    @DisplayName("잠금이 만료되면 다음 시도가 리더가 된다")
    void expiredLockCanBeTaken() throws Exception {
        Lead first = a.tryLead(TYPE, key, Duration.ofMillis(200));
        Thread.sleep(300);

        Lead second = b.tryLead(TYPE, key, Duration.ofSeconds(5));

        assertThat(second.mine()).isTrue();
        assertThat(second.token()).isNotEqualTo(first.token());
    }

    @Test
    @DisplayName("none() 은 늘 리더다")
    void noneAlwaysLeads() {
        CrossInstanceFlightPort none = CrossInstanceFlightPort.none();
        assertThat(none.tryLead(TYPE, key, Duration.ofSeconds(1)).mine()).isTrue();
        assertThat(none.currentHolder(TYPE, key)).isEmpty();
    }
}
