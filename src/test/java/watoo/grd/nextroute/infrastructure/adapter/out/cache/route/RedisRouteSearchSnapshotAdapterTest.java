package watoo.grd.nextroute.infrastructure.adapter.out.cache.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import watoo.grd.nextroute.application.route.config.RouteSnapshotProperties;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort.StoredPath;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스냅샷 저장소 계약을 실 Redis(Testcontainers)로 고정한다 — 직렬화 왕복·TTL 만료·잘못된 pathKey.
 *
 * <p>가장 중요한 불변식: 이 어댑터는 어떤 경우에도 예외를 밖으로 던지지 않는다.
 * 검색 응답 경로에서 호출되기 때문이다(PLAN §4.3).
 */
class RedisRouteSearchSnapshotAdapterTest {

    private static GenericContainer<?> redis;
    private static LettuceConnectionFactory connectionFactory;
    private static RedisTemplate<String, String> redisTemplate;

    private ObjectMapper objectMapper;
    private RouteSnapshotProperties properties;
    private RedisRouteSearchSnapshotAdapter adapter;

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

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        properties = new RouteSnapshotProperties();
        adapter = new RedisRouteSearchSnapshotAdapter(redisTemplate, objectMapper, properties);
    }

    // ── 저장·조회 왕복 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("저장한 검색 결과를 pathKey 로 되찾는다 — 키·직렬화 계약 고정")
    void saveThenFindRoundTrip() {
        Instant expiresAt = Instant.now().plusSeconds(600);

        String sessionId = adapter.save(42L, result(3), expiresAt);

        assertThat(sessionId).isNotBlank();
        assertThat(redisTemplate.hasKey("route:snapshot:" + sessionId)).isTrue();

        Optional<StoredPath> found = adapter.find(sessionId, "1");

        assertThat(found).isPresent();
        assertThat(found.get().ownerUserId()).isEqualTo(42L);
        assertThat(found.get().path().pathKey()).isEqualTo("1");
        assertThat(found.get().path().info().totalTime()).isEqualTo(11);   // 직렬화 왕복 후에도 값 보존
        assertThat(found.get().expiresAt().toEpochMilli()).isEqualTo(expiresAt.toEpochMilli());
    }

    @Test
    @DisplayName("익명 검색은 ownerUserId 가 null 로 저장된다")
    void anonymousSearchStoresNullOwner() {
        String sessionId = adapter.save(null, result(1), Instant.now().plusSeconds(600));

        assertThat(adapter.find(sessionId, "0")).isPresent();
        assertThat(adapter.find(sessionId, "0").get().ownerUserId()).isNull();
    }

    @Test
    @DisplayName("저장 키에 설정된 TTL 이 실제로 걸린다 — 무기한 키가 아니다")
    void savedKeyHasTtl() {
        properties.setTtlSeconds(600);

        String sessionId = adapter.save(1L, result(1), Instant.now().plusSeconds(600));

        Long ttl = redisTemplate.getExpire("route:snapshot:" + sessionId, TimeUnit.SECONDS);
        assertThat(ttl).isBetween(1L, 600L);
    }

    // ── 만료 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("TTL 이 지나면 같은 searchSessionId 조회가 empty 다")
    void findAfterTtlExpiryIsEmpty() throws Exception {
        properties.setTtlSeconds(1);

        String sessionId = adapter.save(1L, result(2), Instant.now().plusSeconds(1));
        assertThat(adapter.find(sessionId, "0")).isPresent();

        TimeUnit.MILLISECONDS.sleep(1500);

        assertThat(adapter.find(sessionId, "0")).isEmpty();
    }

    @Test
    @DisplayName("없는 searchSessionId 는 empty 다")
    void findUnknownSessionIsEmpty() {
        assertThat(adapter.find("nope-not-a-session", "0")).isEmpty();
    }

    // ── 잘못된 pathKey ────────────────────────────────────────────────────────

    @Test
    @DisplayName("범위 밖 pathKey 는 empty 다 — 예외를 던지지 않는다")
    void findOutOfRangePathKeyIsEmpty() {
        String sessionId = adapter.save(1L, result(3), Instant.now().plusSeconds(600));

        assertThat(adapter.find(sessionId, "99")).isEmpty();
        assertThat(adapter.find(sessionId, "-1")).isEmpty();
    }

    @Test
    @DisplayName("숫자가 아닌 pathKey 는 empty 다 — 예외를 던지지 않는다")
    void findNonNumericPathKeyIsEmpty() {
        String sessionId = adapter.save(1L, result(3), Instant.now().plusSeconds(600));

        assertThat(adapter.find(sessionId, "abc")).isEmpty();
        assertThat(adapter.find(sessionId, "")).isEmpty();
        assertThat(adapter.find(sessionId, null)).isEmpty();
        assertThat(adapter.find(null, "0")).isEmpty();
    }

    @Test
    @DisplayName("깨진 JSON 이 들어 있어도 empty 로 흡수한다")
    void corruptedValueIsEmpty() {
        redisTemplate.opsForValue().set("route:snapshot:broken", "{not json");

        assertThat(adapter.find("broken", "0")).isEmpty();
    }

    // ── Redis 장애 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis 장애: save 는 null 을 돌려주고 find 는 empty — 예외를 밖으로 던지지 않는다")
    void redisFailureIsAbsorbed() {
        RedisRouteSearchSnapshotAdapter broken = new RedisRouteSearchSnapshotAdapter(
                template(brokenFactory()), objectMapper, properties);

        assertThat(broken.save(1L, result(1), Instant.now().plusSeconds(600))).isNull();
        assertThat(broken.find("any-session", "0")).isEmpty();
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    /** 연결 불가 포트를 향하는 factory — Redis 장애 재현. */
    private static LettuceConnectionFactory brokenFactory() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redis.getHost(), 1);
        factory.setTimeout(200);
        factory.afterPropertiesSet();
        return factory;
    }

    /** pathKey 까지 채워진, 저장 직전 형태의 검색 결과. */
    private static RouteSearchResult result(int pathCount) {
        List<PathResult> paths = IntStream.range(0, pathCount)
                .mapToObj(i -> new PathResult(1, info(10 + i), List.of(), List.of()))
                .toList();
        return new RouteSearchResult(0, 0, 1, 0, 0, paths).withPathKeys();
    }

    private static PathInfo info(int totalTime) {
        return new PathInfo(totalTime, 1250, 300, 1, "강남", "판교", "mapObj");
    }
}
