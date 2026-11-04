package watoo.grd.nextroute.infrastructure.adapter.out.api.gyeonggi;

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
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
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

/**
 * 경기 도착 어댑터의 서버 공유 경로(bus-multi-instance PR-4): 같은 Redis 를 보는 어댑터 세 개(각자 RestClient + JVM SingleFlight)에
 * 동시 요청이 몰려도 공급자 호출은 플래그 켬 1회, 끔 3회. 몫은 캐시 miss 뒤 공급자 직전에만 센다.
 */
@DisplayName("경기 도착 어댑터 서버 공유")
class GyeonggiBusArrivalAdapterSharedIT {

    private static final String BASE = "https://apis.data.go.kr/6410000/busarrivalservice/v2";
    private static final int SERVERS = 3;
    private static final int REQUESTS = 60;
    private static final String NO_RESULT = """
            {"response":{"comMsgHeader":"","msgHeader":{"resultCode":4,"resultMessage":"결과가 존재하지 않습니다."}}}
            """;
    private static final String ITEM = """
            {"response":{"comMsgHeader":"","msgHeader":{"queryTime":"2026-10-05 16:30:25.865","resultCode":0,
            "resultMessage":"정상"},"msgBody":{"busArrivalItem":{"crowded1":1,"crowded2":1,"flag":"PASS",
            "locationNo1":1,"locationNo2":19,"predictTime1":1,"predictTime2":29,"remainSeatCnt1":33,
            "remainSeatCnt2":39,"routeId":228000184,"routeName":5600,"staOrder":62,"stationId":123000001}}}}
            """;

    private LettuceConnectionFactory factory;
    private RedisTemplate<String, String> redis;
    private String station;
    private AtomicInteger providerCalls;
    private ExecutorService pool;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-03T03:00:00Z"), ZoneOffset.UTC);
    private final List<MockRestServiceServer> servers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        factory = RedisTestSupport.factory();
        redis = RedisTestSupport.template(factory);
        station = "it-" + UUID.randomUUID();
        providerCalls = new AtomicInteger();
        pool = Executors.newFixedThreadPool(REQUESTS);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        redis.keys("bus:*" + station + "*").forEach(redis::delete);
        redis.keys("bus:quota:search:gg-arrival:*").forEach(redis::delete);
        factory.destroy();
    }

    private RegionalBusShared shared(boolean enabled, int dailyLimit) {
        RegionalBusSharedProperties props = new RegionalBusSharedProperties();
        props.setEnabled(enabled);
        props.setGyeonggiDailyLimit(dailyLimit);
        return new RegionalBusShared(props,
                new RedisRegionalBusCacheAdapter(redis, new ObjectMapper(), props),
                new RedisCrossInstanceFlight(redis, new ObjectMapper()),
                new RedisBusApiQuota(redis, new BusApiQuotaProperties(), clock),
                new RedisNamedSemaphore(redis),
                new RouteSearchMetrics(new SimpleMeterRegistry()));
    }

    /** 서버 하나 = RestClient 하나(응답마다 호출 수를 세고 300ms 쉰다) + 어댑터 하나. */
    private GyeonggiBusArrivalAdapter server(String body, RegionalBusShared shared) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.between(0, REQUESTS), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(req -> {
                    providerCalls.incrementAndGet();
                    sleepQuietly(300);
                    return withSuccess(body, MediaType.APPLICATION_JSON).createResponse(req);
                });
        servers.add(server);
        return new GyeonggiBusArrivalAdapter(builder.build(), new ObjectMapper(), new ApiCallBlocker(), clock, BASE, "K%2F", shared,
                (stationId, routeId) -> java.util.OptionalInt.of(7));
    }

    private List<BusQueryResult<GyeonggiBusArrival>> fire(List<GyeonggiBusArrivalAdapter> adapters) throws Exception {
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
        List<BusQueryResult<GyeonggiBusArrival>> results = new ArrayList<>();
        for (Future<BusQueryResult<GyeonggiBusArrival>> f : futures) {
            results.add(f.get(15, TimeUnit.SECONDS));
        }
        return results;
    }

    @Test
    @DisplayName("플래그 켬: 서버 셋에 동시 요청이 몰려도 공급자 호출 1회, 몫도 1 만 쓴다")
    void enabledCollapsesToOneCall() throws Exception {
        RegionalBusShared shared = shared(true, 5000);
        List<GyeonggiBusArrivalAdapter> adapters = List.of(server(ITEM, shared), server(ITEM, shared), server(ITEM, shared));

        List<BusQueryResult<GyeonggiBusArrival>> results = fire(adapters);

        assertThat(providerCalls.get()).isEqualTo(1);
        assertThat(results).allMatch(r -> r.isOk() && r.data().size() == 1);
        assertThat(redis.opsForValue().get(quotaKey())).as("몫은 공급자 호출 1회분").isEqualTo("1");
    }

    @Test
    @DisplayName("플래그 켬: 빈 결과(resultCode 4)도 공유 캐시에 남아 다음 조회가 공급자를 안 부른다")
    void emptyResultIsCachedAcrossServers() throws Exception {
        RegionalBusShared shared = shared(true, 5000);
        GyeonggiBusArrivalAdapter a = server(NO_RESULT, shared);
        GyeonggiBusArrivalAdapter b = server(NO_RESULT, shared);

        assertThat(a.getArrival(station, "228000184").isOk()).isTrue();
        assertThat(b.getArrival(station, "228000184").cacheHit()).as("다른 서버의 빈 결과를 캐시에서").isTrue();
        assertThat(providerCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("플래그 끔(대조군): 서버 수만큼 부른다")
    void disabledCallsPerServer() throws Exception {
        RegionalBusShared shared = shared(false, 5000);
        List<GyeonggiBusArrivalAdapter> adapters = List.of(server(ITEM, shared), server(ITEM, shared), server(ITEM, shared));

        fire(adapters);

        // JVM 캐시 put 이 flight 안에서 일어나 드물게 한 번 더 부를 수 있다 — 대조군의 요지는 "서버 수 이상"
        assertThat(providerCalls.get()).isGreaterThanOrEqualTo(SERVERS);
    }

    @Test
    @DisplayName("플래그 켬: 순번이 안 정해지면 공급자를 안 부르고 몫도 안 쓴다")
    void noUniqueOrderChargesNoQuota() {
        RegionalBusShared shared = shared(true, 1);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder).build();
        GyeonggiBusArrivalAdapter adapter = new GyeonggiBusArrivalAdapter(builder.build(), new ObjectMapper(),
                new ApiCallBlocker(), clock, BASE, "K%2F", shared,
                (stationId, routeId) -> java.util.OptionalInt.empty());

        assertThat(adapter.getArrival(station, "228000184").isOk()).isFalse();

        assertThat(redis.keys("bus:quota:search:gg-arrival:*")).isEmpty();
    }

    @Test
    @DisplayName("몫 소진이면 공급자를 안 부르고 LIMITED, 캐시 hit 는 몫을 안 쓴다, 재탐색(chargeQuota=false)은 몫을 안 센다")
    void quotaGatesProviderOnly() throws Exception {
        RegionalBusShared shared = shared(true, 1);
        GyeonggiBusArrivalAdapter a = server(ITEM, shared);

        assertThat(a.getArrival(station, "228000184").isOk()).isTrue();        // 몫 1 사용
        assertThat(a.getArrival(station, "228000184").cacheHit()).isTrue();    // hit — 몫 그대로
        assertThat(a.getArrival(station, "other").outcome()).isEqualTo(Outcome.LIMITED);   // 소진
        assertThat(a.getArrival(station, "other2", false).isOk()).as("재탐색은 자기 버킷을 이미 냈다").isTrue();
        assertThat(providerCalls.get()).isEqualTo(2);
        assertThat(redis.opsForValue().get(quotaKey())).isEqualTo("2");
    }

    private String quotaKey() {
        return "bus:quota:search:gg-arrival:20261103";
    }

    private static void sleepQuietly(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
