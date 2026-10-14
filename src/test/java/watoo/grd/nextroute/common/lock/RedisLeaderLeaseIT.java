package watoo.grd.nextroute.common.lock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리더 잠금이 실제 Redis 에서 한 대만 리더로 세우는지.
 *
 * <p>REDIS_HOST·REDIS_PORT 의 Redis 를 쓴다(다른 IT 와 같은 일회용 Redis). 시험마다 키를 새로 만든다.
 */
@DisplayName("리더 잠금")
class RedisLeaderLeaseIT {

    private LettuceConnectionFactory factory;
    private RedisTemplate<String, String> redis;
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
        key = "lease:it:" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        redis.delete(key);
        factory.destroy();
    }

    @Test
    @DisplayName("두 인스턴스가 동시에 시도해도 한 대만 리더가 된다")
    void onlyOneLeader() {
        RedisLeaderLease a = lease(10_000);
        RedisLeaderLease b = lease(10_000);

        a.tick();
        b.tick();

        assertThat(a.isLeader()).isTrue();
        assertThat(b.isLeader()).isFalse();
    }

    @Test
    @DisplayName("리더가 아닌 쪽은 남의 잠금을 늘리거나 지우지 못한다")
    void nonOwnerCannotRenewOrRelease() {
        RedisLeaderLease a = lease(10_000);
        RedisLeaderLease b = lease(10_000);
        a.tick();

        b.tick();
        b.stop();

        assertThat(redis.opsForValue().get(key)).as("b 의 해제가 a 의 잠금을 지우면 안 된다").isNotNull();
        a.tick();
        assertThat(a.isLeader()).isTrue();
    }

    @Test
    @DisplayName("리더가 죽어 잠금이 만료되면 다른 인스턴스가 이어받고, 옛 리더는 갱신에 실패해 내려온다")
    void takeoverAfterExpiry() throws Exception {
        RedisLeaderLease a = lease(300);
        RedisLeaderLease b = lease(300);
        a.tick();
        assertThat(a.isLeader()).isTrue();
        long epochBefore = a.epoch();

        Thread.sleep(450); // a 가 갱신하지 못한 채 만료
        assertThat(a.isLeader()).as("로컬 마감도 지나 스스로 리더가 아니라고 답한다").isFalse();
        b.tick();
        assertThat(b.isLeader()).isTrue();

        a.tick();
        assertThat(a.isLeader()).isFalse();
        assertThat(a.epoch()).as("자격을 잃은 것이 epoch 에 남는다").isGreaterThan(epochBefore);
    }

    @Test
    @DisplayName("꺼져 있으면 Redis 없이도 늘 리더다 — worker 한 대인 지금과 같다")
    void disabledIsAlwaysLeader() {
        RedisLeaderLease off = new RedisLeaderLease(redis, new SimpleMeterRegistry(), false, key, 10_000);

        assertThat(off.isLeader()).isTrue();
    }

    private RedisLeaderLease lease(long ttlMs) {
        return new RedisLeaderLease(redis, new SimpleMeterRegistry(), true, key, ttlMs);
    }

    @Test
    @DisplayName("켜져 있어도 worker 가 아닌 인스턴스(api)는 경쟁하지 않고 리더도 아니다")
    void nonWorkerNeverLeads() {
        RedisLeaderLease api = lease(10_000);
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles("api");
        api.setEnvironment(env);

        api.start();

        assertThat(api.isLeader()).isFalse();
        assertThat(redis.opsForValue().get(key)).as("api 가 키를 잡으면 worker 수집이 멈춘다").isNull();
        api.stop();
    }
}
