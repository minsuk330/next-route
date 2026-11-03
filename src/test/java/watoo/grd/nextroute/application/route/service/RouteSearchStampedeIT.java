package watoo.grd.nextroute.application.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import watoo.grd.nextroute.application.route.port.out.CrossInstanceFlightPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.application.bus.BusArrivalInfoFixtures;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.route.config.SearchTimeBusProperties;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.infrastructure.adapter.out.api.bus.CachedSearchTimeBusAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.api.bus.SearchTimeBusAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RedisBusRealtimeCacheAdapter;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Step 3 검증 — 같은 키를 동시에 miss 하는 요청이 provider 를 몇 번 호출하는가.
 *
 * <p>캐시가 만료되는 순간이 가장 위험하다. 같은 정류장·노선을 보는 요청이 동시에 miss 하면
 * 그 수만큼 provider 를 호출하게 되고, 이는 일일 예산이 있는 공공 API 에서 그대로 비용이 된다.
 */
class RouteSearchStampedeIT {

    private static final String STOP = "stop-1";
    private static final String ROUTE = "route-1";
    private static final int SEQ = 3;
    private static final int CONCURRENCY = 100;
    /** provider 응답 지연 — 이 창 동안 다른 요청들이 몰려든다. */
    private static final long PROVIDER_LATENCY_MS = 200;

    private static GenericContainer<?> redis;
    private static LettuceConnectionFactory connectionFactory;
    private static RedisTemplate<String, String> redisTemplate;

    private SimpleMeterRegistry registry;
    private RouteSearchMetrics metrics;
    private AtomicInteger providerCalls;
    private CachedSearchTimeBusAdapter cache;

    @BeforeAll
    static void startRedis() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        redis.start();
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new StringRedisSerializer());
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        redis.stop();
    }

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        registry = new SimpleMeterRegistry();
        metrics = new RouteSearchMetrics(registry);
        providerCalls = new AtomicInteger();

        TransferArrivalProperties props = new TransferArrivalProperties();
        props.setCacheTtlSeconds(15);

        SearchTimeBusAdapter provider = mock(SearchTimeBusAdapter.class);
        when(provider.getArrInfoByStop(anyString(), anyString(), anyInt())).thenAnswer(inv -> {
            providerCalls.incrementAndGet();
            TimeUnit.MILLISECONDS.sleep(PROVIDER_LATENCY_MS);   // 호출이 겹칠 창을 만든다
            return BusQueryResult.ok(List.of(arrival()));
        });

        RedisBusRealtimeCacheAdapter redisCache =
                new RedisBusRealtimeCacheAdapter(redisTemplate, new ObjectMapper(), props, metrics);
        cache = new CachedSearchTimeBusAdapter(provider, redisCache, redisCache, metrics,
                new SearchTimeBusProperties(), CrossInstanceFlightPort.none());
    }

    @Test
    @DisplayName("동일 키 100건 동시 miss — provider 호출은 1회, 나머지는 결과를 공유받는다")
    void concurrentMissCollapsesToSingleProviderCall() throws Exception {
        List<BusQueryResult<BusArrivalInfo>> results = runConcurrentSearches();

        System.out.printf("%n=== stampede: 동일 키 %d건 동시 miss (provider %dms) ===%n",
                CONCURRENCY, PROVIDER_LATENCY_MS);
        System.out.printf("  provider 호출: %d회 (single-flight 없으면 %d회)%n",
                providerCalls.get(), CONCURRENCY);
        System.out.printf("  owner=%,.0f joiner=%,.0f join_timeout=%,.0f%n",
                singleFlightCount("owner"), singleFlightCount("joiner"), singleFlightCount("join_timeout"));

        // 핵심: 호출이 1회로 접힌다.
        assertThat(providerCalls.get()).isEqualTo(1);
        assertThat(singleFlightCount("owner")).isEqualTo(1);
        assertThat(singleFlightCount("joiner")).isEqualTo(CONCURRENCY - 1);
        assertThat(singleFlightCount("join_timeout")).isZero();

        // 모든 요청이 같은 데이터를 받는다 — joiner 도 값을 잃지 않는다.
        assertThat(results).hasSize(CONCURRENCY);
        assertThat(results).allSatisfy(r -> {
            assertThat(r.isOk()).isTrue();
            assertThat(r.data()).hasSize(1);
            assertThat(r.data().get(0).kalPredictTime1()).isEqualTo(120);
        });
    }

    @Test
    @DisplayName("병합된 호출 이후에는 캐시가 받는다 — 두 번째 물결은 provider 호출 0회")
    void secondWaveIsServedByCache() throws Exception {
        runConcurrentSearches();
        assertThat(providerCalls.get()).isEqualTo(1);

        List<BusQueryResult<BusArrivalInfo>> second = runConcurrentSearches();

        // owner 가 채운 캐시를 두 번째 물결이 그대로 쓴다.
        assertThat(providerCalls.get()).isEqualTo(1);
        assertThat(second).allSatisfy(r -> assertThat(r.cacheHit()).isTrue());
        assertThat(cacheCount("hit")).isEqualTo(CONCURRENCY);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private List<BusQueryResult<BusArrivalInfo>> runConcurrentSearches() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch startGate = new CountDownLatch(1);
        List<java.util.concurrent.Future<BusQueryResult<BusArrivalInfo>>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < CONCURRENCY; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();   // 동시에 출발시켜 miss 를 겹치게 한다
                return cache.getArrInfoByStop(STOP, ROUTE, SEQ);
            }));
        }
        startGate.countDown();

        List<BusQueryResult<BusArrivalInfo>> results = new java.util.ArrayList<>();
        for (var f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }

    private static BusArrivalInfo arrival() {
        return BusArrivalInfoFixtures.arrivalWithKalPredict1(120);
    }

    private double singleFlightCount(String role) {
        Counter c = registry.find(RouteSearchMetrics.SINGLEFLIGHT)
                .tag("provider", "bus").tag("type", "arrival").tag("role", role).counter();
        return c == null ? 0 : c.count();
    }

    private double cacheCount(String result) {
        Counter c = registry.find(RouteSearchMetrics.CACHE_REQUESTS)
                .tag("type", "arrival").tag("result", result).counter();
        return c == null ? 0 : c.count();
    }
}
