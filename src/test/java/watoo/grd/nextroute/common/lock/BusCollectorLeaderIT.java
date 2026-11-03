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
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.bus.config.BusApiQuotaProperties;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.BusArrivalScheduler;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RedisBusApiQuota;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * worker 두 대가 같은 Redis 를 볼 때 버스 수집이 한 대만 돌고, 수집 몫이 서버 수와 무관하게 하나인지(PR-1 계약).
 *
 * <p>{@code common.lock} 패키지에 두는 이유: {@link RedisLeaderLease#tick()} 이 package-private 이다. REDIS_HOST·REDIS_PORT 의
 * 일회용 Redis 를 쓴다.
 */
@DisplayName("버스 수집기 리더 한 대")
class BusCollectorLeaderIT {

    private LettuceConnectionFactory factory;
    private RedisTemplate<String, String> redis;
    private String leaseKey;
    private String bucket;

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
        leaseKey = "lease:it:bus-collector:" + UUID.randomUUID();
        bucket = "it-collector-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        redis.delete(leaseKey);
        redis.keys("bus:quota:search:" + bucket + ":*").forEach(redis::delete);
        factory.destroy();
    }

    private RedisLeaderLease lease() {
        return new RedisLeaderLease(redis, new SimpleMeterRegistry(), true, leaseKey, 10_000);
    }

    private BusArrivalScheduler scheduler(AtomicInteger executions, LeaderLease lease) {
        BusArrivalScheduler s = new BusArrivalScheduler(executions::incrementAndGet, endpoint -> Optional.empty(), lease);
        ReflectionTestUtils.setField(s, "enabled", true);
        ReflectionTestUtils.setField(s, "activeStart", "00:00");
        ReflectionTestUtils.setField(s, "activeEnd", "00:00");
        return s;
    }

    @Test
    @DisplayName("두 worker 가 같은 주기에 trigger 해도 수집은 한 번만 돈다")
    void onlyLeaderCollects() {
        RedisLeaderLease a = lease();
        RedisLeaderLease b = lease();
        a.tick();
        b.tick();
        AtomicInteger runsA = new AtomicInteger();
        AtomicInteger runsB = new AtomicInteger();

        scheduler(runsA, a).trigger();
        scheduler(runsB, b).trigger();

        assertThat(runsA.get() + runsB.get()).as("한 주기에 수집 한 바퀴").isEqualTo(1);
        assertThat(a.isLeader()).isTrue();
        assertThat(b.isLeader()).isFalse();
    }

    @Test
    @DisplayName("수집 몫 버킷은 두 서버가 합쳐 한도에서 멈춘다")
    void quotaIsSharedAcrossInstances() {
        BusApiQuotaPort quotaA = new RedisBusApiQuota(redis, new BusApiQuotaProperties(), Clock.systemUTC());
        BusApiQuotaPort quotaB = new RedisBusApiQuota(redis, new BusApiQuotaProperties(), Clock.systemUTC());

        assertThat(quotaA.tryAcquire(bucket, 3)).isTrue();
        assertThat(quotaB.tryAcquire(bucket, 3)).isTrue();
        assertThat(quotaA.tryAcquire(bucket, 3)).isTrue();
        assertThat(quotaB.tryAcquire(bucket, 3)).as("합이 한도를 넘으면 어느 서버든 거절").isFalse();
        assertThat(quotaA.tryAcquire(bucket, 3)).isFalse();
    }
}
