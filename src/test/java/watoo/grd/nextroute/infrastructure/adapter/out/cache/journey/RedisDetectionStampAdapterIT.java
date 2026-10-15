package watoo.grd.nextroute.infrastructure.adapter.out.cache.journey;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.DetectionStamp;
import watoo.grd.nextroute.application.journey.reeval.ReevaluationTier;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 처음 본 시각 칸의 계약(#446)을 실 Redis 로 고정한다. 조용히 틀어지는 것 넷 —
 * <ul>
 *   <li>나중 관측이 더 이른 관측을 덮음 → 처음부터 끝까지 지연이 짧게 재진다</li>
 *   <li>꺼낸 뒤에도 남음 → 다음 평가가 옛 관측을 물려받아 길게 재진다</li>
 *   <li>수명 없는 키 → 끝난 여정의 칸이 영원히 남는다</li>
 *   <li>Redis 장애가 평가·트리거로 번짐 — 측정 전용이라 삼켜야 한다</li>
 * </ul>
 */
@DisplayName("처음 본 시각 칸 — 먼저 본 것만 남고, 한 번 꺼내면 비고, 장애는 삼킨다")
class RedisDetectionStampAdapterIT {

    private static final Instant T0 = Instant.parse("2026-10-16T09:00:00Z");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static RedisTemplate<String, String> template;

    private final JourneyNotificationProperties properties = new JourneyNotificationProperties();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private RedisDetectionStampAdapter adapter;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        factory = connect(REDIS.getHost(), REDIS.getMappedPort(6379));
        template = templateOf(factory);
    }

    @AfterAll
    static void stopRedis() {
        factory.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        template.getConnectionFactory().getConnection().serverCommands().flushAll();
        adapter = new RedisDetectionStampAdapter(template, properties, registry);
    }

    @Test
    @DisplayName("이미 있으면 덮지 않는다 — 처음 본 시각과 그때의 등급이 남는다")
    void firstObservationWins() {
        adapter.stamp(Map.of(ReevaluationTier.CAUTION, Set.of(1L)), T0);
        adapter.stamp(Map.of(ReevaluationTier.URGENT, Set.of(1L, 2L)), T0.plusSeconds(15));

        assertThat(adapter.consume(1L)).contains(DetectionStamp.feed(T0, ReevaluationTier.CAUTION));
        assertThat(adapter.consume(2L)).contains(DetectionStamp.feed(T0.plusSeconds(15), ReevaluationTier.URGENT));
    }

    @Test
    @DisplayName("한 번 꺼내면 빈다 — 그 뒤 관측은 다음 평가 몫이다")
    void consumeDeletes() {
        adapter.stamp(Map.of(ReevaluationTier.URGENT, Set.of(3L)), T0);

        assertThat(adapter.consume(3L)).isPresent();
        assertThat(adapter.consume(3L)).isEmpty();
        adapter.stamp(Map.of(ReevaluationTier.RELAXED, Set.of(3L)), T0.plusSeconds(30));
        assertThat(adapter.consume(3L)).contains(DetectionStamp.feed(T0.plusSeconds(30), ReevaluationTier.RELAXED));
    }

    @Test
    @DisplayName("수명이 있다(끝난 여정의 칸이 남지 않게)")
    void hasTtl() {
        adapter.stamp(Map.of(ReevaluationTier.URGENT, Set.of(4L)), T0);

        Long ttl = template.getExpire("journey:seen:4");
        assertThat(ttl).isBetween(1L, properties.getE2e().getStampTtlSeconds());
    }

    @Test
    @DisplayName("되돌리기는 더 이른 쪽을 남긴다 — 평가 못 한 사이 새 관측이 들어와도 첫 관측이 이긴다")
    void restoreKeepsEarliest() {
        DetectionStamp early = DetectionStamp.feed(T0, ReevaluationTier.URGENT);
        adapter.stamp(Map.of(ReevaluationTier.CAUTION, Set.of(5L)), T0.plusSeconds(15));
        adapter.restore(5L, early);
        assertThat(adapter.consume(5L)).contains(early);

        adapter.stamp(Map.of(ReevaluationTier.CAUTION, Set.of(6L)), T0);
        adapter.restore(6L, DetectionStamp.feed(T0.plusSeconds(15), ReevaluationTier.URGENT));
        assertThat(adapter.consume(6L)).contains(DetectionStamp.feed(T0, ReevaluationTier.CAUTION));

        // 평가 시작 시각(하한)은 되돌리지 않는다 — 다음 평가가 자기 시작 시각을 쓴다.
        adapter.restore(7L, DetectionStamp.evaluation(T0, ReevaluationTier.URGENT));
        assertThat(adapter.consume(7L)).isEmpty();
    }

    @Test
    @DisplayName("꺼져 있으면 남기지도 꺼내지도 않는다")
    void disabledIsNoop() {
        properties.getE2e().setEnabled(false);
        adapter.stamp(Map.of(ReevaluationTier.URGENT, Set.of(8L)), T0);

        assertThat(template.hasKey("journey:seen:8")).isFalse();
        assertThat(adapter.consume(8L)).isEmpty();
    }

    @Test
    @DisplayName("Redis 가 죽어 있으면 삼킨다 — 측정 칸이 평가·트리거를 실패시키지 않는다")
    void redisDownIsSwallowed() {
        LettuceConnectionFactory dead = connect("127.0.0.1", 1);
        try {
            RedisDetectionStampAdapter down = new RedisDetectionStampAdapter(templateOf(dead), properties, registry);

            assertThatCode(() -> down.stamp(Map.of(ReevaluationTier.URGENT, Set.of(9L)), T0))
                    .doesNotThrowAnyException();
            assertThat(down.consume(9L)).isEmpty();
            assertThatCode(() -> down.restore(9L, DetectionStamp.feed(T0, ReevaluationTier.URGENT)))
                    .doesNotThrowAnyException();
            assertThat(registry.find(RedisDetectionStampAdapter.METRIC).tag("result", "error").counter().count())
                    .isEqualTo(3.0);
        } finally {
            dead.destroy();
        }
    }

    private static LettuceConnectionFactory connect(String host, int port) {
        LettuceConnectionFactory f = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1)).build());
        f.afterPropertiesSet();
        return f;
    }

    private static RedisTemplate<String, String> templateOf(LettuceConnectionFactory f) {
        RedisTemplate<String, String> t = new RedisTemplate<>();
        t.setConnectionFactory(f);
        t.setKeySerializer(new StringRedisSerializer());
        t.setValueSerializer(new StringRedisSerializer());
        t.afterPropertiesSet();
        return t;
    }
}
