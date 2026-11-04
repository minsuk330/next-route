package watoo.grd.nextroute.infrastructure.adapter.out.api.gyeonggi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.arrival.config.RegionalBusSharedProperties;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.bus.config.BusApiQuotaProperties;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
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

/**
 * 측정 A(bus-multi-instance PR-3), 경기 어댑터 편: 서버 수 1~4 × {@code regional-bus.shared.enabled} 끔/켬에서 같은 정류장
 * 동시 100건이 공급자를 몇 번 부르는지. 서울 편({@code CachedSearchTimeBusAdapterCrossInstanceBenchmarkTest})과 같은 표.
 * 쓰는 숫자는 호출 수뿐이다. 실행: {@code RUN_BENCHMARK=true ./gradlew cleanTest test --tests '*SharedBenchmark*'}(환경 변수는 Gradle 입력이 아니라
 * {@code cleanTest} 없이는 UP-TO-DATE 로 건너뛴다). 플래그 켬의 호출 수는 서버 수와 무관하게 1 이어야 한다(단언).
 */
@EnabledIfEnvironmentVariable(named = "RUN_BENCHMARK", matches = "true")
class GyeonggiBusArrivalAdapterSharedBenchmarkTest {

    private static final int REQUESTS = 100;
    private static final int WARMUP = 2;
    private static final int ROUNDS = 10;
    private static final long PROVIDER_MS = 73;
    private static final String BASE = "https://apis.data.go.kr/6410000/busarrivalservice/v2";
    private static final String ITEM = """
            {"response":{"comMsgHeader":"","msgHeader":{"queryTime":"2026-10-05 16:30:25.865","resultCode":0,
            "resultMessage":"정상"},"msgBody":{"busArrivalItem":{"crowded1":1,"crowded2":1,"flag":"PASS",
            "locationNo1":1,"locationNo2":19,"predictTime1":1,"predictTime2":29,"remainSeatCnt1":33,
            "remainSeatCnt2":39,"routeId":228000184,"routeName":5600,"staOrder":62,"stationId":123000001}}}}
            """;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-03T03:00:00Z"), ZoneOffset.UTC);
    private LettuceConnectionFactory factory;
    private RedisTemplate<String, String> redis;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        factory = RedisTestSupport.factory();
        redis = RedisTestSupport.template(factory);
        pool = Executors.newFixedThreadPool(REQUESTS);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        // 고정 Clock(2026-11-03 KST 12:00)이 쓰는 날짜 버킷만 지운다 — 다른 날짜(실제 오늘)의 몫은 건드리지 않는다.
        redis.delete("bus:quota:search:gg-arrival:20261103");
        factory.destroy();
    }

    @Test
    void providerCallsByServerCount() throws Exception {
        System.out.printf("%n=== 경기 공유 캐시: 동시 %d건이 공급자를 부른 횟수 (예열 %d, %d회 중앙값/최대) ===%n",
                REQUESTS, WARMUP, ROUNDS);
        System.out.printf("%-8s %-6s %12s %12s%n", "servers", "flag", "calls(med)", "calls(max)");
        for (int servers = 1; servers <= 4; servers++) {
            for (boolean on : new boolean[]{false, true}) {
                List<Integer> calls = new ArrayList<>();
                for (int round = 0; round < WARMUP + ROUNDS; round++) {
                    AtomicInteger counter = new AtomicInteger();
                    String station = "bench-" + UUID.randomUUID();
                    RegionalBusShared shared = shared(on);
                    List<GyeonggiBusArrivalAdapter> adapters = new ArrayList<>();
                    for (int i = 0; i < servers; i++) {
                        adapters.add(server(counter, shared));
                    }
                    try {
                        fire(adapters, station);
                        if (round >= WARMUP) {
                            calls.add(counter.get());
                        }
                    } finally {
                        redis.keys("bus:*" + station + "*").forEach(redis::delete);
                    }
                }
                calls.sort(Integer::compareTo);
                int median = calls.get(calls.size() / 2);
                int max = calls.get(calls.size() - 1);
                System.out.printf("%-8d %-6s %12d %12d%n", servers, on ? "on" : "off", median, max);
                if (on) {
                    assertThat(max).as("flag on, %d servers: provider calls per round", servers).isEqualTo(1);
                } else {
                    assertThat(median).as("flag off, %d servers: provider calls per round", servers).isEqualTo(servers);
                }
            }
        }
    }

    private RegionalBusShared shared(boolean enabled) {
        RegionalBusSharedProperties props = new RegionalBusSharedProperties();
        props.setEnabled(enabled);
        return new RegionalBusShared(props,
                new RedisRegionalBusCacheAdapter(redis, new ObjectMapper(), props),
                new RedisCrossInstanceFlight(redis, new ObjectMapper()),
                new RedisBusApiQuota(redis, new BusApiQuotaProperties(), clock),
                new RedisNamedSemaphore(redis),
                new RouteSearchMetrics(new SimpleMeterRegistry()));
    }

    private GyeonggiBusArrivalAdapter server(AtomicInteger counter, RegionalBusShared shared) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.between(0, REQUESTS), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(req -> {
                    counter.incrementAndGet();
                    sleepQuietly(PROVIDER_MS);
                    return withSuccess(ITEM, MediaType.APPLICATION_JSON).createResponse(req);
                });
        return new GyeonggiBusArrivalAdapter(builder.build(), new ObjectMapper(), new ApiCallBlocker(), clock, BASE, "K%2F", shared,
                (stationId, routeId) -> java.util.OptionalInt.of(7));
    }

    private void fire(List<GyeonggiBusArrivalAdapter> adapters, String station) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BusQueryResult<GyeonggiBusArrival>>> futures = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            GyeonggiBusArrivalAdapter a = adapters.get(i % adapters.size());
            futures.add(pool.submit(() -> {
                start.await();
                return a.getArrival(station, "228000184");
            }));
        }
        start.countDown();
        for (Future<BusQueryResult<GyeonggiBusArrival>> f : futures) {
            BusQueryResult<GyeonggiBusArrival> r = f.get(30, TimeUnit.SECONDS);
            if (!r.isOk()) {
                throw new IllegalStateException("not ok: " + r.outcome());
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
