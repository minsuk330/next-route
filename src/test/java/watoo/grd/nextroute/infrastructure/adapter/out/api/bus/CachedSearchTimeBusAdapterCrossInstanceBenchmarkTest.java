package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import watoo.grd.nextroute.application.bus.BusArrivalInfoFixtures;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.route.config.SearchTimeBusProperties;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.CrossInstanceFlightPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RedisBusRealtimeCacheAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RedisCrossInstanceFlight;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RedisTestSupport;

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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 측정 A(bus-multi-instance PR-3): 서버 수 1~4 × 플래그 끔/켬에서 같은 정류장 동시 100건이 공급자를 몇 번 부르는지.
 * 바꾸는 것은 플래그 하나뿐. 공급자 지연은 사다리 가짜 공급자의 보정값(BUS_MS=73)을 쓴다 — 운영 Prometheus 에는 외부 API
 * 히스토그램이 없다(2026-11-03 읽기 전용 확인). 쓰는 숫자는 호출 수뿐이다(한 프로세스 + 로컬 Redis 라 지연은 배포를 대표하지
 * 않음, 플랜 검토 #18). 실행: {@code RUN_BENCHMARK=true ./gradlew cleanTest test --tests '*CrossInstanceBenchmark*'}(환경 변수는 Gradle 입력이 아니라
 * {@code cleanTest} 없이는 UP-TO-DATE 로 건너뛴다). 플래그 켬의 호출 수는 서버 수와 무관하게 1 이어야 한다(단언).
 */
@EnabledIfEnvironmentVariable(named = "RUN_BENCHMARK", matches = "true")
class CachedSearchTimeBusAdapterCrossInstanceBenchmarkTest {

    private static final int REQUESTS = 100;
    private static final int WARMUP = 2;
    private static final int ROUNDS = 10;
    private static final long PROVIDER_MS = 73;

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
        factory.destroy();
    }

    @Test
    void providerCallsByServerCount() throws Exception {
        System.out.printf("%n=== 서버 사이 single-flight: 동시 %d건이 공급자를 부른 횟수 (예열 %d, %d회 중앙값/최대) ===%n",
                REQUESTS, WARMUP, ROUNDS);
        System.out.printf("%-8s %-6s %12s %12s %14s%n", "servers", "flag", "calls(med)", "calls(max)", "p50 ms(참고)");
        for (int servers = 1; servers <= 4; servers++) {
            for (boolean on : new boolean[]{false, true}) {
                List<Integer> calls = new ArrayList<>();
                List<Long> p50s = new ArrayList<>();
                for (int round = 0; round < WARMUP + ROUNDS; round++) {
                    AtomicInteger counter = new AtomicInteger();
                    String stop = "bench-" + UUID.randomUUID();
                    List<CachedSearchTimeBusAdapter> adapters = new ArrayList<>();
                    for (int i = 0; i < servers; i++) {
                        adapters.add(server(counter, on));
                    }
                    try {
                        long[] latencies = fire(adapters, stop);
                        if (round >= WARMUP) {
                            calls.add(counter.get());
                            java.util.Arrays.sort(latencies);
                            p50s.add(latencies[latencies.length / 2]);
                        }
                    } finally {
                        redis.keys("bus:*" + stop + "*").forEach(redis::delete);
                    }
                }
                calls.sort(Integer::compareTo);
                p50s.sort(Long::compareTo);
                int median = calls.get(calls.size() / 2);
                int max = calls.get(calls.size() - 1);
                System.out.printf("%-8d %-6s %12d %12d %14d%n", servers, on ? "on" : "off",
                        median, max, p50s.get(p50s.size() / 2));
                if (on) {
                    assertThat(max).as("flag on, %d servers: provider calls per round", servers).isEqualTo(1);
                } else {
                    assertThat(median).as("flag off, %d servers: provider calls per round", servers).isEqualTo(servers);
                }
            }
        }
    }

    private CachedSearchTimeBusAdapter server(AtomicInteger counter, boolean on) {
        SearchTimeBusAdapter provider = mock(SearchTimeBusAdapter.class);
        when(provider.getArrInfoByStop(anyString(), anyString(), anyInt())).thenAnswer(inv -> {
            counter.incrementAndGet();
            TimeUnit.MILLISECONDS.sleep(PROVIDER_MS);
            return BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalWithSectionTime(60, null)));
        });
        TransferArrivalProperties cacheProps = new TransferArrivalProperties();
        RouteSearchMetrics metrics = new RouteSearchMetrics(new SimpleMeterRegistry());
        RedisBusRealtimeCacheAdapter cache = new RedisBusRealtimeCacheAdapter(redis, new ObjectMapper(), cacheProps, metrics);
        SearchTimeBusProperties props = new SearchTimeBusProperties();
        props.getCrossInstance().setEnabled(on);
        CrossInstanceFlightPort flight = on ? new RedisCrossInstanceFlight(redis, new ObjectMapper()) : CrossInstanceFlightPort.none();
        return new CachedSearchTimeBusAdapter(provider, cache, cache, metrics, props, flight);
    }

    private long[] fire(List<CachedSearchTimeBusAdapter> adapters, String stop) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            CachedSearchTimeBusAdapter a = adapters.get(i % adapters.size());
            futures.add(pool.submit(() -> {
                start.await();
                long t0 = System.nanoTime();
                BusQueryResult<BusArrivalInfo> r = a.getArrInfoByStop(stop, "R1", 3);
                if (!r.isOk()) {
                    throw new IllegalStateException("not ok: " + r.outcome());
                }
                return (System.nanoTime() - t0) / 1_000_000L;
            }));
        }
        start.countDown();
        long[] out = new long[REQUESTS];
        for (int i = 0; i < REQUESTS; i++) {
            out[i] = futures.get(i).get(30, TimeUnit.SECONDS);
        }
        return out;
    }
}
