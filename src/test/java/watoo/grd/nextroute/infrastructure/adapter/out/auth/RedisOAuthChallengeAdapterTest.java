package watoo.grd.nextroute.infrastructure.adapter.out.auth;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.application.auth.config.OAuthProviderProperties;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 일회용 값이 정말 한 번만 통하는지 실제 Redis 로 확인한다.
 *
 * <p>가짜 저장소로는 확인할 수 없는 것이 둘 있다. 유지 시간이 실제로 걸리는지, 그리고
 * 확인과 삭제가 원자적으로 일어나는지다. 확인 후 따로 지우면 그 사이에 재사용이 통과한다.
 */
class RedisOAuthChallengeAdapterTest {

    private static GenericContainer<?> redis;
    private static LettuceConnectionFactory connectionFactory;
    private static RedisTemplate<String, String> redisTemplate;

    private OAuthProviderProperties properties;
    private RedisOAuthChallengeAdapter adapter;

    @BeforeAll
    static void startRedis() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        redis.start();
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = template(connectionFactory);
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        redis.stop();
    }

    private static RedisTemplate<String, String> template(RedisConnectionFactory factory) {
        RedisTemplate<String, String> t = new RedisTemplate<>();
        t.setConnectionFactory(factory);
        t.setKeySerializer(new StringRedisSerializer());
        t.setValueSerializer(new StringRedisSerializer());
        t.afterPropertiesSet();
        return t;
    }

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        properties = new OAuthProviderProperties();
        properties.getChallenge().setTtlSeconds(300);
        adapter = new RedisOAuthChallengeAdapter(redisTemplate, properties);
    }

    @Test
    @DisplayName("발급한 값은 한 번 통하고 두 번째는 막힌다")
    void issuedNonce_consumedExactlyOnce() {
        String nonce = adapter.issue();

        assertThat(adapter.consume(nonce)).isTrue();
        assertThat(adapter.consume(nonce)).isFalse();
    }

    @Test
    @DisplayName("발급한 적 없는 값은 통하지 않는다")
    void unknownNonce_rejected() {
        assertThat(adapter.consume("never-issued")).isFalse();
    }

    @Test
    @DisplayName("빈 값은 저장소를 건드리지도 않는다")
    void blankNonce_rejected() {
        assertThat(adapter.consume(null)).isFalse();
        assertThat(adapter.consume("")).isFalse();
        assertThat(adapter.consume("   ")).isFalse();
    }

    @Test
    @DisplayName("발급값은 매번 다르다 — 같으면 남의 challenge 를 재사용할 수 있다")
    void issuedNonces_areUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(adapter.issue());
        }
        assertThat(seen).hasSize(200);
    }

    @Test
    @DisplayName("발급값에 유지 시간이 걸린다 — 무기한 남는 키가 아니다")
    void issuedNonce_hasTtl() {
        String nonce = adapter.issue();

        Long ttl = redisTemplate.getExpire("oauth:challenge:" + nonce, TimeUnit.SECONDS);
        assertThat(ttl).isBetween(1L, 300L);
    }

    @Test
    @DisplayName("유지 시간이 지나면 통하지 않는다")
    void expiredNonce_rejected() throws Exception {
        properties.getChallenge().setTtlSeconds(1);
        String nonce = adapter.issue();

        TimeUnit.MILLISECONDS.sleep(1500);

        assertThat(adapter.consume(nonce)).isFalse();
    }

    @Test
    @DisplayName("Redis 장애면 막는 쪽으로 실패한다 — 통과시키면 재전송 방어가 사라진다")
    void redisFailure_rejects() {
        LettuceConnectionFactory broken = new LettuceConnectionFactory(redis.getHost(), 1);
        broken.setTimeout(200);
        broken.afterPropertiesSet();
        RedisOAuthChallengeAdapter brokenAdapter =
                new RedisOAuthChallengeAdapter(template(broken), properties);

        assertThat(brokenAdapter.consume("any-nonce")).isFalse();
    }
}
