package watoo.grd.nextroute.application.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import watoo.grd.nextroute.application.bus.BusArrivalInfoFixtures;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.config.SearchTimeBusProperties;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.infrastructure.adapter.out.api.bus.CachedSearchTimeBusAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.api.bus.SearchTimeBusAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RedisBusRealtimeCacheAdapter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 1 — 이미 존재하는 hybrid cache(수집기 refresh-ahead + 검색 cache-aside)의
 * <b>계약을 고정하고 효과를 수치화</b>한다. 실 Redis(Testcontainers)로 직렬화·TTL·만료까지 포함한다.
 *
 * <p>확인 대상(PLAN.md §6 Step 1):
 * <ol>
 *   <li>수집기 write 와 검색 read 가 같은 키/직렬화 계약을 쓴다</li>
 *   <li>cache hit 은 provider 를 호출하지 않는다(호출 cap·quota 미소모)</li>
 *   <li>빈 결과·차단·오류는 캐시에 저장되지 않는다(빈 결과 고착 방지)</li>
 *   <li>Redis 장애는 miss 로 degrade 하고 검색은 provider 경로로 계속된다</li>
 *   <li>TTL 별 hit ratio·provider 호출 수·hit 시점 데이터 age</li>
 * </ol>
 */
class RouteSearchCacheIT {

    private static final String STOP = "stop-1";
    private static final String ROUTE = "route-1";
    private static final int SEQ = 3;

    private static GenericContainer<?> redis;
    private static LettuceConnectionFactory connectionFactory;
    private static RedisTemplate<String, String> redisTemplate;

    private SimpleMeterRegistry registry;
    private RouteSearchMetrics metrics;
    private TransferArrivalProperties props;
    private SearchTimeBusAdapter provider;
    private CachedSearchTimeBusAdapter cache;
    private RedisBusRealtimeCacheAdapter redisCache;

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

        registry = new SimpleMeterRegistry();
        metrics = new RouteSearchMetrics(registry);
        props = new TransferArrivalProperties();
        props.setCacheTtlSeconds(15);   // 운영 기본값

        provider = mock(SearchTimeBusAdapter.class);
        redisCache = new RedisBusRealtimeCacheAdapter(redisTemplate, new ObjectMapper(), props, metrics);
        cache = new CachedSearchTimeBusAdapter(provider, redisCache, redisCache, metrics,
                new SearchTimeBusProperties());
    }

    // ── 1. 수집기 write ↔ 검색 read 계약 ─────────────────────────────────────

    @Test
    @DisplayName("수집기가 적재한 값을 검색이 그대로 읽는다 — 키·직렬화 계약 고정")
    void collectorPrimedValueIsReadBySearch() {
        // 수집기 경로(프라이밍): 같은 어댑터의 write port 로 적재
        redisCache.putArrivals(STOP, ROUTE, SEQ, List.of(arrival(180)));

        BusQueryResult<BusArrivalInfo> result = cache.getArrInfoByStop(STOP, ROUTE, SEQ);

        assertThat(result.cacheHit()).isTrue();
        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).kalPredictTime1()).isEqualTo(180);   // 직렬화 왕복 후에도 값 보존
        verifyNoProviderCall();

        // TTL 이 실제로 걸려 있다(무기한 키 아님)
        Long ttl = redisTemplate.getExpire("bus:search:arr:" + STOP + ":" + ROUTE + ":" + SEQ, TimeUnit.SECONDS);
        assertThat(ttl).isBetween(1L, 15L);
    }

    @Test
    @DisplayName("cache hit 은 provider 를 호출하지 않는다 — 검색 호출 cap·quota 미소모")
    void hitDoesNotCallProvider() {
        redisCache.putArrivals(STOP, ROUTE, SEQ, List.of(arrival(120)));

        for (int i = 0; i < 5; i++) {
            assertThat(cache.getArrInfoByStop(STOP, ROUTE, SEQ).cacheHit()).isTrue();
        }

        verifyNoProviderCall();
        assertThat(cacheCount("arrival", "hit")).isEqualTo(5);
        assertThat(cacheCount("arrival", "miss")).isZero();
    }

    @Test
    @DisplayName("miss 는 provider 를 호출하고 non-empty 결과만 적재한다")
    void missCallsProviderAndCachesNonEmpty() {
        when(provider.getArrInfoByStop(anyString(), anyString(), anyInt()))
                .thenReturn(BusQueryResult.ok(List.of(arrival(90))));

        BusQueryResult<BusArrivalInfo> first = cache.getArrInfoByStop(STOP, ROUTE, SEQ);
        assertThat(first.cacheHit()).isFalse();

        BusQueryResult<BusArrivalInfo> second = cache.getArrInfoByStop(STOP, ROUTE, SEQ);
        assertThat(second.cacheHit()).isTrue();

        verify(provider, org.mockito.Mockito.times(1)).getArrInfoByStop(STOP, ROUTE, SEQ);
        assertThat(cacheCount("arrival", "miss")).isEqualTo(1);
        assertThat(cacheCount("arrival", "hit")).isEqualTo(1);
    }

    // ── 2. 저장하면 안 되는 결과 ─────────────────────────────────────────────

    @Test
    @DisplayName("빈 결과·차단·제한·오류는 캐시에 남지 않는다 — 빈 결과 고착 방지")
    void nonStorableOutcomesAreNotCached() {
        List<BusQueryResult<BusArrivalInfo>> notStorable = List.of(
                BusQueryResult.ok(List.of()),      // 정상 조회했지만 버스 없음
                BusQueryResult.blocked(),          // breaker 차단
                BusQueryResult.limited(),          // quota·동시성 상한
                BusQueryResult.error()             // 호출 예외
        );

        for (BusQueryResult<BusArrivalInfo> outcome : notStorable) {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
            when(provider.getArrInfoByStop(anyString(), anyString(), anyInt())).thenReturn(outcome);

            cache.getArrInfoByStop(STOP, ROUTE, SEQ);

            String key = "bus:search:arr:" + STOP + ":" + ROUTE + ":" + SEQ;
            assertThat(redisTemplate.hasKey(key))
                    .as("outcome=%s 는 캐시에 저장되면 안 된다", outcome.outcome())
                    .isFalse();
        }
    }

    // ── 3. Redis 장애 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis 장애: read 는 miss 로 degrade 하고 검색은 provider 경로로 계속된다")
    void redisFailureDegradesToMiss() {
        RedisTemplate<String, String> broken = template(brokenFactory());
        RedisBusRealtimeCacheAdapter brokenCache =
                new RedisBusRealtimeCacheAdapter(broken, new ObjectMapper(), props, metrics);
        CachedSearchTimeBusAdapter searchCache =
                new CachedSearchTimeBusAdapter(provider, brokenCache, brokenCache, metrics,
                new SearchTimeBusProperties());

        when(provider.getArrInfoByStop(anyString(), anyString(), anyInt()))
                .thenReturn(BusQueryResult.ok(List.of(arrival(60))));

        BusQueryResult<BusArrivalInfo> result = searchCache.getArrInfoByStop(STOP, ROUTE, SEQ);

        // 검색 자체는 살아있다.
        assertThat(result.isOk()).isTrue();
        assertThat(result.data()).hasSize(1);
        assertThat(result.cacheHit()).isFalse();
        verify(provider).getArrInfoByStop(STOP, ROUTE, SEQ);

        // 검색 입장에선 miss, 원인은 error 지표로 분리 기록된다.
        assertThat(cacheCount("arrival", "miss")).isEqualTo(1);
        assertThat(cacheErrorCount("arrival", "read")).isEqualTo(1);
        assertThat(cacheErrorCount("arrival", "write")).isEqualTo(1);
    }

    // ── 4. TTL A/B ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("TTL A/B — hit ratio·provider 호출·hit 시점 데이터 age")
    void ttlSweep() throws Exception {
        int windowSeconds = 20;
        long tickMs = 250;

        System.out.printf("%n=== TTL A/B (동일 키 1개, %ds 동안 %dms 간격 조회) ===%n",
                windowSeconds, tickMs);
        System.out.printf("%-8s %10s %10s %10s %12s %10s %10s%n",
                "TTL", "requests", "hits", "provider", "hit ratio", "age p50", "age max");

        // 대조군: TTL 0 = 캐시 우회(read miss, write no-op). 모든 요청이 provider 로 간다.
        TtlSweepResult noCache = runWindow(0, windowSeconds, tickMs);
        System.out.printf("%-8s %10d %10d %10d %11.1f%% %9s %9s%n",
                "off", noCache.requests, noCache.hits, noCache.providerCalls, 0.0, "-", "-");
        assertThat(noCache.hits).isZero();
        assertThat(noCache.providerCalls).isEqualTo(noCache.requests);

        for (int ttl : new int[]{5, 15}) {
            TtlSweepResult r = runWindow(ttl, windowSeconds, tickMs);
            System.out.printf("%-8s %10d %10d %10d %11.1f%% %9ds %9ds%n",
                    ttl + "s", r.requests, r.hits, r.providerCalls,
                    100.0 * r.hits / r.requests, r.ageP50, r.ageMax);

            // provider 호출은 window/TTL 회 수준으로 수렴한다(캐시가 없으면 requests 회 전부 호출).
            assertThat(r.providerCalls).isLessThanOrEqualTo((windowSeconds / ttl) + 1);
            assertThat(r.providerCalls).isLessThan(r.requests);
            // hit 데이터의 staleness 상한은 TTL 그 자체다 — 알림 판단이 물려받는 오차.
            assertThat(r.ageMax).isLessThanOrEqualTo(ttl);
        }

        System.out.println("""
                주: age = hit 시점 데이터의 경과 시간(TTL - 남은 TTL). 최대 staleness = TTL.
                   같은 캐시를 버스 알림의 발송 조건 판단도 공유하므로, TTL 상한은 검색 hit ratio 가
                   아니라 알림이 허용하는 도착 판단 오차가 결정한다(PLAN.md §4).
                """);
    }

    private record TtlSweepResult(int requests, int hits, int providerCalls, long ageP50, long ageMax) {
    }

    private TtlSweepResult runWindow(int ttlSeconds, int windowSeconds, long tickMs) throws Exception {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        TransferArrivalProperties sweepProps = new TransferArrivalProperties();
        sweepProps.setCacheTtlSeconds(ttlSeconds);

        SimpleMeterRegistry sweepRegistry = new SimpleMeterRegistry();
        RouteSearchMetrics sweepMetrics = new RouteSearchMetrics(sweepRegistry);
        RedisBusRealtimeCacheAdapter sweepCache =
                new RedisBusRealtimeCacheAdapter(redisTemplate, new ObjectMapper(), sweepProps, sweepMetrics);

        AtomicInteger providerCalls = new AtomicInteger();
        SearchTimeBusAdapter sweepProvider = mock(SearchTimeBusAdapter.class);
        when(sweepProvider.getArrInfoByStop(anyString(), anyString(), anyInt()))
                .thenAnswer(inv -> {
                    providerCalls.incrementAndGet();
                    return BusQueryResult.ok(List.of(arrival(120)));
                });

        CachedSearchTimeBusAdapter sweepSearchCache =
                new CachedSearchTimeBusAdapter(sweepProvider, sweepCache, sweepCache, sweepMetrics,
                        new SearchTimeBusProperties());

        String key = "bus:search:arr:" + STOP + ":" + ROUTE + ":" + SEQ;
        List<Long> ages = new ArrayList<>();
        int requests = 0;
        int hits = 0;

        long deadline = System.nanoTime() + Duration.ofSeconds(windowSeconds).toNanos();
        while (System.nanoTime() < deadline) {
            BusQueryResult<BusArrivalInfo> result = sweepSearchCache.getArrInfoByStop(STOP, ROUTE, SEQ);
            requests++;
            if (result.cacheHit()) {
                hits++;
                Long remaining = redisTemplate.getExpire(key, TimeUnit.SECONDS);
                if (remaining != null && remaining >= 0) {
                    ages.add(ttlSeconds - remaining);   // 적재 후 경과 시간
                }
            }
            TimeUnit.MILLISECONDS.sleep(tickMs);
        }

        ages.sort(Long::compareTo);
        long p50 = ages.isEmpty() ? 0 : ages.get(ages.size() / 2);
        long max = ages.isEmpty() ? 0 : ages.get(ages.size() - 1);
        return new TtlSweepResult(requests, hits, providerCalls.get(), p50, max);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private void verifyNoProviderCall() {
        verify(provider, never()).getArrInfoByStop(anyString(), anyString(), anyInt());
    }

    /** 연결 불가 포트를 향하는 factory — Redis 장애 재현. */
    private static LettuceConnectionFactory brokenFactory() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redis.getHost(), 1);
        factory.setTimeout(200);
        factory.afterPropertiesSet();
        return factory;
    }

    /** 최소 fixture — 이 테스트가 보는 값은 kalPredictTime1 하나면 충분하다. */
    private static BusArrivalInfo arrival(int predictSeconds) {
        return BusArrivalInfoFixtures.arrivalWithKalPredict1(predictSeconds);
    }

    private double cacheCount(String type, String result) {
        Counter c = registry.find(RouteSearchMetrics.CACHE_REQUESTS)
                .tag("type", type).tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    private double cacheErrorCount(String type, String op) {
        Counter c = registry.find(RouteSearchMetrics.CACHE_ERRORS)
                .tag("type", type).tag("op", op).counter();
        return c == null ? 0 : c.count();
    }
}
