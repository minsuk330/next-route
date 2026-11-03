package watoo.grd.nextroute.infrastructure.adapter.out.api.tago;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.arrival.config.RegionalBusSharedProperties;
import watoo.grd.nextroute.application.arrival.dto.TagoBusArrival;
import watoo.grd.nextroute.application.bus.config.BusApiQuotaProperties;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.Outcome;
import watoo.grd.nextroute.infrastructure.adapter.out.api.ApiCallBlocker;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.RedisNamedSemaphore;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RedisBusApiQuota;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RedisCrossInstanceFlight;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RedisRegionalBusCacheAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RedisTestSupport;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RegionalBusShared;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** TAGO 도착 어댑터의 서버 공유 경로(PR-4): 동시 요청 묶기 + 서버 합산 동시 호출 상한. */
@DisplayName("TAGO 도착 어댑터 서버 공유")
class TagoBusArrivalAdapterSharedIT {

    private static final String BASE = "https://apis.data.go.kr/1613000/ArvlInfoInqireService";
    private static final int SERVERS = 3;
    private static final int REQUESTS = 60;
    private static final String ONE_BUS = """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":
            {"arrprevstationcnt":21,"arrtime":1791,"nodeid":"GMB119","nodenm":"구미역","routeid":"GMB171-110",
             "routeno":"171-1","routetp":"일반버스","vehicletp":"일반차량"}},"numOfRows":10,"pageNo":1,"totalCount":1}}}
            """;

    private LettuceConnectionFactory factory;
    private RedisTemplate<String, String> redis;
    private String node;
    private AtomicInteger providerCalls;
    private ExecutorService pool;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-03T03:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        factory = RedisTestSupport.factory();
        redis = RedisTestSupport.template(factory);
        node = "GMB" + UUID.randomUUID();
        redis.delete("sem:" + TagoBusArrivalAdapter.SHARED_SEMAPHORE + ":permits");   // 죽은 회차의 lease 잔재 제거
        providerCalls = new AtomicInteger();
        pool = Executors.newFixedThreadPool(REQUESTS);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        redis.keys("bus:*" + node + "*").forEach(redis::delete);
        redis.delete("sem:" + TagoBusArrivalAdapter.SHARED_SEMAPHORE + ":permits");
        factory.destroy();
    }

    private RegionalBusShared shared(boolean enabled, int maxConcurrent) {
        RegionalBusSharedProperties props = new RegionalBusSharedProperties();
        props.setEnabled(enabled);
        props.setTagoMaxConcurrent(maxConcurrent);
        return new RegionalBusShared(props,
                new RedisRegionalBusCacheAdapter(redis, new ObjectMapper(), props),
                new RedisCrossInstanceFlight(redis, new ObjectMapper()),
                new RedisBusApiQuota(redis, new BusApiQuotaProperties(), clock),
                new RedisNamedSemaphore(redis),
                new RouteSearchMetrics(new SimpleMeterRegistry()));
    }

    private TagoBusArrivalAdapter server(RegionalBusShared shared, long latencyMs) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.between(0, REQUESTS), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(req -> {
                    providerCalls.incrementAndGet();
                    sleepQuietly(latencyMs);
                    return withSuccess(ONE_BUS, MediaType.APPLICATION_JSON).createResponse(req);
                });
        return new TagoBusArrivalAdapter(builder.build(), new ObjectMapper(), new ApiCallBlocker(), clock, BASE, "K%2F", shared);
    }

    @Test
    @DisplayName("플래그 켬: 서버 셋에 같은 정류장 동시 요청이 몰려도 공급자 호출 1회")
    void enabledCollapsesToOneCall() throws Exception {
        RegionalBusShared shared = shared(true, 8);
        List<TagoBusArrivalAdapter> adapters = List.of(server(shared, 300), server(shared, 300), server(shared, 300));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BusQueryResult<TagoBusArrival>>> futures = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            TagoBusArrivalAdapter a = adapters.get(i % SERVERS);
            futures.add(pool.submit(() -> {
                start.await();
                return a.getArrivals("37050", node, "GMB171-110");
            }));
        }
        start.countDown();
        for (Future<BusQueryResult<TagoBusArrival>> f : futures) {
            assertThat(f.get(15, TimeUnit.SECONDS).isOk()).isTrue();
        }
        assertThat(providerCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("플래그 켬: 서로 다른 정류장 동시 조회는 서버 합산 상한을 넘지 않고, 넘친 쪽은 LIMITED")
    void sharedConcurrencyCapAcrossServers() throws Exception {
        RegionalBusShared shared = shared(true, 2);
        TagoBusArrivalAdapter a = server(shared, 3000);
        TagoBusArrivalAdapter b = server(shared, 3000);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BusQueryResult<TagoBusArrival>>> futures = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            TagoBusArrivalAdapter s = i % 2 == 0 ? a : b;
            String stop = node + "-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return s.getArrivals("37050", stop, "GMB171-110");
            }));
        }
        start.countDown();
        long ok = 0;
        long limited = 0;
        for (Future<BusQueryResult<TagoBusArrival>> f : futures) {
            BusQueryResult<TagoBusArrival> r = f.get(15, TimeUnit.SECONDS);
            if (r.isOk()) ok++;
            if (r.outcome() == Outcome.LIMITED) limited++;
        }
        // 자리 2 × (1초 안에 자리 대기) — 3초 호출이라 첫 2건만 자리를 얻고 나머지 4건은 1초 대기 뒤 LIMITED
        assertThat(ok).isEqualTo(2);
        assertThat(limited).isEqualTo(4);
        assertThat(providerCalls.get()).isEqualTo(2);
    }

    private static void sleepQuietly(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
