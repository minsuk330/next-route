package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import watoo.grd.nextroute.application.route.config.SearchTimeBusProperties;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.CrossInstanceFlightPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.Outcome;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RedisBusRealtimeCacheAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RedisCrossInstanceFlight;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 서버 사이 single-flight 의 핵심 계약(bus-multi-instance PR-2): 같은 Redis 를 보는 어댑터 세 개(api 1 + worker 2 흉내,
 * 각자 JVM SingleFlight)에 동시 요청이 몰려도 공급자 호출은 플래그 켬 1회, 끔 3회.
 *
 * <p>REDIS_HOST·REDIS_PORT 의 일회용 Redis. 요청은 latch 로 동시에 출발한다(늦게 출발한 요청은 캐시 hit 가 되어
 * 호출 수를 못 센다 — 플랜 검토 #18).
 */
@DisplayName("서버 사이 single-flight 어댑터")
class CachedSearchTimeBusAdapterCrossInstanceIT {

    private static final int SERVERS = 3;
    private static final int REQUESTS = 100;
    private static final long PROVIDER_LATENCY_MS = 300;

    private LettuceConnectionFactory factory;
    private RedisTemplate<String, String> redis;
    private String stop;
    private AtomicInteger providerCalls;
    private ExecutorService pool;

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
        stop = "it-" + UUID.randomUUID();
        providerCalls = new AtomicInteger();
        pool = Executors.newFixedThreadPool(REQUESTS);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        redis.keys("bus:*" + stop + "*").forEach(redis::delete);
        factory.destroy();
    }

    private SearchTimeBusAdapter provider(Supplier<BusQueryResult<BusArrivalInfo>> answer) {
        SearchTimeBusAdapter provider = mock(SearchTimeBusAdapter.class);
        org.mockito.stubbing.Answer<BusQueryResult<BusArrivalInfo>> slow = inv -> {
            providerCalls.incrementAndGet();
            TimeUnit.MILLISECONDS.sleep(PROVIDER_LATENCY_MS);
            return answer.get();
        };
        when(provider.getArrInfoByStop(anyString(), anyString(), anyInt())).thenAnswer(slow);
        when(provider.getArrInfoByStop(anyString(), anyString(), anyInt(), anyLong())).thenAnswer(slow);
        return provider;
    }

    /** 서버 하나 = 어댑터 하나(자기 JVM SingleFlight). 캐시와 flight 는 같은 Redis. */
    private CachedSearchTimeBusAdapter server(SearchTimeBusAdapter provider, boolean crossInstance) {
        TransferArrivalProperties cacheProps = new TransferArrivalProperties();
        cacheProps.setCacheTtlSeconds(15);
        RouteSearchMetrics metrics = new RouteSearchMetrics(new SimpleMeterRegistry());
        RedisBusRealtimeCacheAdapter cache = new RedisBusRealtimeCacheAdapter(redis, new ObjectMapper(), cacheProps, metrics);
        SearchTimeBusProperties props = new SearchTimeBusProperties();
        props.getCrossInstance().setEnabled(crossInstance);
        CrossInstanceFlightPort flight = crossInstance
                ? new RedisCrossInstanceFlight(redis, new ObjectMapper())
                : CrossInstanceFlightPort.none();
        return new CachedSearchTimeBusAdapter(provider, cache, cache, metrics, props, flight);
    }

    private List<BusQueryResult<BusArrivalInfo>> fire(List<CachedSearchTimeBusAdapter> servers) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BusQueryResult<BusArrivalInfo>>> futures = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            CachedSearchTimeBusAdapter s = servers.get(i % servers.size());
            futures.add(pool.submit(() -> {
                start.await();
                return s.getArrInfoByStop(stop, "R1", 3);
            }));
        }
        start.countDown();
        List<BusQueryResult<BusArrivalInfo>> results = new ArrayList<>();
        for (Future<BusQueryResult<BusArrivalInfo>> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        return results;
    }

    private List<CachedSearchTimeBusAdapter> servers(SearchTimeBusAdapter provider, boolean crossInstance) {
        List<CachedSearchTimeBusAdapter> list = new ArrayList<>();
        for (int i = 0; i < SERVERS; i++) {
            list.add(server(provider, crossInstance));
        }
        return list;
    }

    @Test
    @DisplayName("플래그 켬: 서버 셋에 동시 100건이 몰려도 공급자 호출 1회, 전부 값을 받는다 (20회 반복)")
    void enabledCollapsesToOneCallAcrossServers() throws Exception {
        for (int round = 0; round < 20; round++) {
            stop = "it-" + UUID.randomUUID();
            providerCalls.set(0);
            SearchTimeBusAdapter provider = provider(() ->
                    BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalWithSectionTime(60, null))));

            List<BusQueryResult<BusArrivalInfo>> results = fire(servers(provider, true));

            assertThat(providerCalls.get()).as("round %d provider calls", round).isEqualTo(1);
            assertThat(results).allMatch(BusQueryResult::isOk);
            assertThat(results).allMatch(r -> r.data().size() == 1);
        }
    }

    @Test
    @DisplayName("플래그 끔(대조군): 같은 조건에서 서버 수만큼 3회 부른다")
    void disabledCallsOncePerServer() throws Exception {
        SearchTimeBusAdapter provider = provider(() ->
                BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalWithSectionTime(60, null))));

        List<BusQueryResult<BusArrivalInfo>> results = fire(servers(provider, false));

        assertThat(providerCalls.get()).isEqualTo(SERVERS);
        assertThat(results).allMatch(BusQueryResult::isOk);
    }

    @Test
    @DisplayName("빈 결과: 대기자는 캐시에 기대지 않고 결과 키로 빈 OK 를 받아 곧 끝난다")
    void emptyResultReachesWaitersQuickly() throws Exception {
        SearchTimeBusAdapter provider = provider(() -> BusQueryResult.ok(List.of()));
        List<CachedSearchTimeBusAdapter> servers = servers(provider, true);

        long t0 = System.nanoTime();
        List<BusQueryResult<BusArrivalInfo>> results = fire(servers);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertThat(providerCalls.get()).isEqualTo(1);
        assertThat(results).allMatch(r -> r.isOk() && r.data().isEmpty());
        // 공급자 300ms + 대기자 폴링(50ms) 몇 번. 캐시에 기댔다면 joinWait 1,800ms 까지 끌렸을 것이다.
        assertThat(elapsedMs).isLessThan(PROVIDER_LATENCY_MS + 50 * 4 + 300);
    }

    @Test
    @DisplayName("BLOCKED(공유 차단기)는 대기자도 그대로 받고 공급자는 1회")
    void blockedIsShared() throws Exception {
        SearchTimeBusAdapter provider = provider(BusQueryResult::blocked);

        List<BusQueryResult<BusArrivalInfo>> results = fire(servers(provider, true));

        assertThat(providerCalls.get()).isEqualTo(1);
        assertThat(results).allMatch(r -> r.outcome() == Outcome.BLOCKED);
    }

    @Test
    @DisplayName("LIMITED 는 리더 서버의 자기 사정일 수 있어 대기 서버가 직접 부른다(서버 수만큼 호출)")
    void limitedMakesWaitersFallBack() throws Exception {
        SearchTimeBusAdapter provider = provider(BusQueryResult::limited);

        List<BusQueryResult<BusArrivalInfo>> results = fire(servers(provider, true));

        // 리더 1회 + 대기 서버 둘의 직접 호출 = 서버 수. 플래그 꺼짐과 같은 가용성.
        assertThat(providerCalls.get()).isEqualTo(SERVERS);
        assertThat(results).allMatch(r -> r.outcome() == Outcome.LIMITED);
    }

    @Test
    @DisplayName("리더의 공급자 호출이 예외로 끝나면 대기 서버는 직접 불러 값을 받는다")
    void leaderExceptionMakesWaitersFallBack() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SearchTimeBusAdapter provider = provider(() -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("provider boom");
            }
            return BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalWithSectionTime(60, null)));
        });
        List<CachedSearchTimeBusAdapter> servers = servers(provider, true);

        CountDownLatch start = new CountDownLatch(1);
        List<Future<BusQueryResult<BusArrivalInfo>>> futures = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            CachedSearchTimeBusAdapter s = servers.get(i % SERVERS);
            futures.add(pool.submit(() -> {
                start.await();
                return s.getArrInfoByStop(stop, "R1", 3);
            }));
        }
        start.countDown();
        int ok = 0;
        int failed = 0;
        for (Future<BusQueryResult<BusArrivalInfo>> f : futures) {
            try {
                BusQueryResult<BusArrivalInfo> r = f.get(10, TimeUnit.SECONDS);
                if (r.isOk() && !r.data().isEmpty()) ok++;
            } catch (java.util.concurrent.ExecutionException e) {
                failed++;   // 리더 서버의 요청들(owner 예외 전파 + JVM joiner 의 JOIN_TIMEOUT→LIMITED 는 ok 아님)
            }
        }
        assertThat(providerCalls.get()).as("리더 1회(실패) + 대기 서버 둘의 직접 호출").isEqualTo(SERVERS);
        assertThat(ok).as("대기 서버 두 대의 요청은 값을 받는다").isGreaterThanOrEqualTo(REQUESTS / SERVERS * 2 - 2);
        assertThat(failed).isGreaterThan(0);
    }

    @Test
    @DisplayName("기한이 짧은 대기자는 joinWait 안에 결과가 없으면 LIMITED(waiter_timeout)")
    void waiterTimesOutWithinItsOwnDeadline() throws Exception {
        SearchTimeBusAdapter provider = provider(() ->
                BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalWithSectionTime(60, null))));
        CachedSearchTimeBusAdapter a = server(provider, true);
        CachedSearchTimeBusAdapter b = server(provider, true);

        Future<BusQueryResult<BusArrivalInfo>> leader = pool.submit(() -> a.getArrInfoByStop(stop, "R1", 3, 2000));
        Thread.sleep(80);   // a 가 잠금을 잡은 뒤
        BusQueryResult<BusArrivalInfo> waiter = b.getArrInfoByStop(stop, "R1", 3, 100);

        assertThat(waiter.outcome()).isEqualTo(Outcome.LIMITED);
        assertThat(leader.get(5, TimeUnit.SECONDS).isOk()).isTrue();
        assertThat(providerCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("결과 키가 깨져 있으면 대기자는 LIMITED")
    void undecodableResultMakesWaiterLimited() throws Exception {
        SearchTimeBusAdapter provider = provider(() ->
                BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalWithSectionTime(60, null))));
        CachedSearchTimeBusAdapter server = server(provider, true);
        RedisCrossInstanceFlight raw = new RedisCrossInstanceFlight(redis, new ObjectMapper());
        String key = stop + ":R1:3";
        CrossInstanceFlightPort.Lead held = raw.tryLead("arrival", key, java.time.Duration.ofSeconds(5));
        redis.opsForValue().set("bus:flight:result:arrival:" + key + ":" + held.token(), "not-json");

        BusQueryResult<BusArrivalInfo> waiter = server.getArrInfoByStop(stop, "R1", 3);

        assertThat(waiter.outcome()).isEqualTo(Outcome.LIMITED);
        assertThat(providerCalls.get()).isZero();
    }

    @Test
    @DisplayName("리더가 잠금만 잡고 죽으면 대기 서버들 중 하나가 잠금 만료 뒤 이어받아 부르고 나머지는 그 결과를 받는다")
    void deadLeaderIsTakenOverByAWaiter() throws Exception {
        SearchTimeBusAdapter provider = provider(() ->
                BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalWithSectionTime(60, null))));
        List<CachedSearchTimeBusAdapter> servers = servers(provider, true);
        RedisCrossInstanceFlight raw = new RedisCrossInstanceFlight(redis, new ObjectMapper());
        // 다른 서버가 잠금을 잡은 채 죽은 상황: 결과 키 없이 잠금만 짧게 남는다.
        assertThat(raw.tryLead("arrival", stop + ":R1:3", java.time.Duration.ofMillis(800)).mine()).isTrue();

        long t0 = System.nanoTime();
        List<BusQueryResult<BusArrivalInfo>> results = fire(servers);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertThat(providerCalls.get()).as("잠금 만료 뒤 한 서버만 이어받아 부른다").isEqualTo(1);
        assertThat(results).allMatch(BusQueryResult::isOk);
        assertThat(elapsedMs).as("잠금 만료(800ms)까지 기다린 뒤 한 번 호출").isBetween(800L, 800L + PROVIDER_LATENCY_MS + 1500L);
    }

    @Test
    @DisplayName("리더 double-check: 다른 서버가 방금 캐시를 채웠으면 공급자를 부르지 않는다")
    void leaderRechecksCacheBeforeCalling() throws Exception {
        SearchTimeBusAdapter provider = provider(() ->
                BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalWithSectionTime(60, null))));
        // 첫 캐시 읽기(miss)와 잠금 획득 사이에 다른 서버가 캐시를 채운 상황을 흉내: 잠금 획득 직전에 채운다.
        TransferArrivalProperties cacheProps = new TransferArrivalProperties();
        RedisBusRealtimeCacheAdapter other = new RedisBusRealtimeCacheAdapter(
                redis, new ObjectMapper(), cacheProps, new RouteSearchMetrics(new SimpleMeterRegistry()));
        SearchTimeBusAdapter spyProvider = mock(SearchTimeBusAdapter.class);
        CrossInstanceFlightPort fillingFlight = new CrossInstanceFlightPort() {
            private final RedisCrossInstanceFlight delegate = new RedisCrossInstanceFlight(redis, new ObjectMapper());
            @Override public Lead tryLead(String type, String key, java.time.Duration lockTtl) {
                other.putArrivals(stop, "R1", 3, List.of(BusArrivalInfoFixtures.arrivalWithSectionTime(90, null)));
                return delegate.tryLead(type, key, lockTtl);
            }
            @Override public void finish(String type, String key, String token, Outcome outcome, List<?> data,
                                         java.time.Duration resultTtl) {
                delegate.finish(type, key, token, outcome, data, resultTtl);
            }
            @Override public <T> java.util.Optional<Peek<T>> peekResult(String type, String key, String token,
                                                                       Class<T> elementType) {
                return delegate.peekResult(type, key, token, elementType);
            }
            @Override public java.util.Optional<String> currentHolder(String type, String key) {
                return delegate.currentHolder(type, key);
            }
        };
        SearchTimeBusProperties props = new SearchTimeBusProperties();
        props.getCrossInstance().setEnabled(true);
        RouteSearchMetrics metrics = new RouteSearchMetrics(new SimpleMeterRegistry());
        RedisBusRealtimeCacheAdapter cache = new RedisBusRealtimeCacheAdapter(redis, new ObjectMapper(), cacheProps, metrics);
        CachedSearchTimeBusAdapter racing = new CachedSearchTimeBusAdapter(spyProvider, cache, cache, metrics, props, fillingFlight);

        BusQueryResult<BusArrivalInfo> result = racing.getArrInfoByStop(stop, "R1", 3);

        assertThat(result.isOk()).isTrue();
        assertThat(result.cacheHit()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(spyProvider);
    }

    @Test
    @DisplayName("Redis 를 못 쓰면 각 서버가 직접 부른다(지금 동작)")
    void redisDownFallsBackToDirectCall() throws Exception {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1));
        dead.afterPropertiesSet();
        RedisTemplate<String, String> deadRedis = new RedisTemplate<>();
        deadRedis.setConnectionFactory(dead);
        deadRedis.setKeySerializer(new StringRedisSerializer());
        deadRedis.setValueSerializer(new StringRedisSerializer());
        deadRedis.afterPropertiesSet();
        SearchTimeBusAdapter provider = provider(() ->
                BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalWithSectionTime(60, null))));
        TransferArrivalProperties cacheProps = new TransferArrivalProperties();
        RouteSearchMetrics metrics = new RouteSearchMetrics(new SimpleMeterRegistry());
        RedisBusRealtimeCacheAdapter cache = new RedisBusRealtimeCacheAdapter(deadRedis, new ObjectMapper(), cacheProps, metrics);
        SearchTimeBusProperties props = new SearchTimeBusProperties();
        props.getCrossInstance().setEnabled(true);
        CachedSearchTimeBusAdapter server = new CachedSearchTimeBusAdapter(
                provider, cache, cache, metrics, props, new RedisCrossInstanceFlight(deadRedis, new ObjectMapper()));
        try {
            BusQueryResult<BusArrivalInfo> result = server.getArrInfoByStop(stop, "R1", 3);
            assertThat(result.isOk()).isTrue();
            assertThat(providerCalls.get()).isEqualTo(1);
        } finally {
            dead.destroy();
        }
    }
}
