package watoo.grd.nextroute.application.journey.load;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.application.bus.BusArrivalInfoFixtures;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.observability.JourneyEvaluationMetrics;
import watoo.grd.nextroute.application.journey.service.JourneyDueClaimService;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationService;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlFeatureVector;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPrediction;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPredictionStatus;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.route.service.PredictionSupportService;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey.JourneyDueEvaluatorScheduler;
import watoo.grd.nextroute.infrastructure.adapter.out.api.bus.SearchTimeBusAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.api.gong.GongApiAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.api.mlpredictor.MlArrivalPredictorAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.api.subway.SeoulSubwayApiAdapter;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

/**
 * ACTIVE 여정을 대량으로 만들어 평가기를 돌리고, SLO 를 지키는지 잰다.
 *
 * <p>재는 것은 넷이다.
 * <ul>
 *   <li><b>평가 지연</b> — 예정 시각(선점 전 {@code next_evaluation_at})부터 결과가 저장될 때까지.
 *       worker 실행 시간만 재면 선점 대기와 큐 대기가 빠져 SLO 정의와 어긋난다</li>
 *   <li><b>적체</b> — 예정 시각이 지났는데 아직 평가되지 않은 여정 수의 시계열</li>
 *   <li><b>동시 선점 겹침</b> — 앞선 선점이 아직 유효한데 같은 여정이 다시 선점된 사건. 0이어야 한다.
 *       같은 여정이 실험 중 여러 번 선점되는 것 자체는 정상이다(평가가 끝나면 다시 예약된다)</li>
 *   <li><b>외부 호출 수</b> — 서로 다른 관심(정류장) 수와 비교한다</li>
 * </ul>
 *
 * <p><b>실제 스케줄러 경로를 그대로 돈다.</b> 선점 서비스와 평가 서비스를 직접 반복 호출하면 배치
 * 완료를 기다리는 시간과 tick 간격이 빠져 지연이 실제보다 좋게 나온다. 재려는 것이 바로 그 대기다.
 *
 * <p>평소에는 돌지 않는다. {@code JOURNEY_LOAD=true} 를 준 실행에서만 켜진다 — 10,000건 실험은
 * 몇 분이 걸려 전체 스위트에 들어가면 안 된다.
 *
 * <pre>
 * JOURNEY_LOAD=true JOURNEY_LOAD_JOURNEYS=10000 JOURNEY_LOAD_INTEREST_RATIO=0.01 \
 *   JOURNEY_LOAD_POOL=4 JOURNEY_LOAD_WINDOW_SEC=90 \
 *   ./gradlew test --tests '*JourneyEvaluatorLoadIT'
 * </pre>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "ml.predictor.enabled=true"
})
@EnabledIfEnvironmentVariable(named = "JOURNEY_LOAD", matches = "true")
@DisplayName("여정 평가 부하")
class JourneyEvaluatorLoadIT {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    /** provider 한 번 왕복에 걸리는 시간. 0 으로 두면 큐도 슬롯도 쌓이지 않아 격리 효과가 안 보인다. */
    private static final long PROVIDER_LATENCY_MS = 50;

    private static final DateTimeFormatter DATA_TM = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired JourneyDueClaimService claimService;
    @Autowired JourneyEvaluatorProperties properties;
    @Autowired JourneyEvaluationMetrics metrics;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired SearchTimeBusQueryPort searchBusPort;

    /** 검색 요청과 평가기가 공유하는 provider 동시 호출 제한. 실제 어댑터 안에 있는 것과 같은 빈이다. */
    @Autowired @Qualifier("busSearchConcurrencyLimiter") Semaphore sharedLimiter;

    @SpyBean JourneyEvaluationService evaluationService;

    @MockBean SearchTimeBusAdapter busDelegate;
    @MockBean MlArrivalPredictorAdapter mlDelegate;
    @MockBean SeoulSubwayApiAdapter subwayDelegate;
    @MockBean PredictionSupportService supportService;
    @MockBean GongApiAdapter holidayCalendar;

    private final AtomicLong arrivalCalls = new AtomicLong();
    private final AtomicLong positionCalls = new AtomicLong();
    private final AtomicLong mlCalls = new AtomicLong();
    private final AtomicLong searchTotal = new AtomicLong();
    private final AtomicLong searchLimited = new AtomicLong();

    private JourneyLoadFixture fixture;
    private JourneyLoadFixture.Prepared prepared;

    @AfterEach
    void cleanUp() {
        if (fixture != null && prepared != null) {
            fixture.cleanUp(prepared);
        }
    }

    @Test
    @DisplayName("설정한 규모로 평가기를 돌리고 지연·적체·호출 수를 남긴다")
    void runScenario() throws Exception {
        int journeys = envInt("JOURNEY_LOAD_JOURNEYS", 1_000);
        double interestRatio = envDouble("JOURNEY_LOAD_INTEREST_RATIO", 0.01);
        int poolSize = envInt("JOURNEY_LOAD_POOL", properties.getPoolSize());
        Duration window = Duration.ofSeconds(envInt("JOURNEY_LOAD_WINDOW_SEC", 60));
        int distinctStops = Math.max(1, (int) Math.round(journeys * interestRatio));
        // 선점 배치와 tick 간격은 처리량 상한을 직접 정한다(배치 ÷ tick). 가설 확인용 손잡이다.
        properties.setBatchSize(envInt("JOURNEY_LOAD_BATCH", properties.getBatchSize()));
        properties.setTickMs(envInt("JOURNEY_LOAD_TICK_MS", (int) properties.getTickMs()));
        properties.setQueueCapacity(envInt("JOURNEY_LOAD_QUEUE", properties.getQueueCapacity()));
        boolean withSearch = "true".equals(System.getenv("JOURNEY_LOAD_WITH_SEARCH"));

        givenProviders();
        JourneyLoadProbe probe = recordEvaluations();

        fixture = new JourneyLoadFixture(jdbc);
        long preparedAt = System.nanoTime();
        prepared = fixture.prepare(new JourneyLoadFixture.Spec(
                journeys, distinctStops, Instant.now(), properties.interval()));
        Duration prepareTook = Duration.ofNanos(System.nanoTime() - preparedAt);

        ThreadPoolTaskExecutor executor = executor(poolSize);
        JourneyDueEvaluatorScheduler scheduler =
                new JourneyDueEvaluatorScheduler(claimService, evaluationService, metrics,
                        properties, executor, java.time.Clock.systemUTC());

        Thread searchLoad = withSearch ? startSearchLoad() : null;
        JourneyLoadProbe.Report report;
        try {
            report = probe.observe(scheduler, window, this::backlog, this::activeConnections,
                    executor, properties.getTickMs());
        } finally {
            executor.shutdown();
            if (searchLoad != null) {
                searchLoad.interrupt();
                searchLoad.join(2_000);
            }
        }

        print(report, journeys, distinctStops, poolSize, window, prepareTook);

        // 실험이 실제로 돌았는지부터 확인한다. 0건이면 아래 수치는 전부 의미가 없다.
        assertThat(report.completed()).isPositive();
        assertThat(report.overlappingClaims()).isZero();
    }

    /**
     * 예정 시각 → 저장 완료 지연을 모으고, 선점이 겹치는지 본다.
     *
     * <p><b>겹침의 정의가 중요하다.</b> 같은 여정이 실험 중 여러 번 선점되는 것은 정상이다 —
     * 평가가 끝나면 다음 평가 시각이 다시 심기고, 환승이 임박하면 그 간격이 10초까지 짧아진다.
     * 문제가 되는 것은 <b>앞선 평가가 아직 끝나지 않았는데</b> 같은 여정이 또 선점되는 경우다.
     * 선점 시각(lease)만 보고 판정하면 정상 재평가를 겹침으로 잘못 센다.
     *
     * <p>실패·건너뜀은 완료로 세지 않는다. 그 여정은 다음 선점에서 다시 계산되고, 그때 기록되는
     * 지연에 기다린 시간이 모두 들어간다.
     */
    private JourneyLoadProbe recordEvaluations() {
        JourneyLoadProbe probe = new JourneyLoadProbe();
        Map<Long, Boolean> inFlight = new ConcurrentHashMap<>();
        willAnswer(call -> {
            JourneyDueClaim claim = call.getArgument(0);
            if (inFlight.putIfAbsent(claim.journeyId(), Boolean.TRUE) != null) {
                probe.recordOverlappingClaim();
            }
            try {
                JourneyEvaluationService.Report result =
                        (JourneyEvaluationService.Report) call.callRealMethod();
                if (result.result() == JourneyEvaluationService.Result.EVALUATED) {
                    probe.recordCompletion(claim.dueAt(), Instant.now());
                } else {
                    probe.recordIncomplete(result.result().name().toLowerCase());
                }
                return result;
            } finally {
                inFlight.remove(claim.journeyId());
            }
        }).given(evaluationService).evaluate(any());
        return probe;
    }

    private void givenProviders() {
        given(supportService.support(anyString()))
                .willReturn(PredictionSupportService.Support.SUPPORTED);
        // 실제 어댑터는 공용 Semaphore 를 잡은 뒤에야 provider 를 부른다. 그 어댑터를 mock 으로
        // 바꿨으므로 여기서 같은 절차를 재현한다 — 그래야 검색 요청과의 경합이 측정에 남는다.
        willAnswer(call -> throughSharedLimiter(() -> {
            arrivalCalls.incrementAndGet();
            // 노선이 달라 실시간으로는 못 잡는다 → 모델 경로까지 태운다(외부 호출 두 종류 모두 관측).
            return BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalWithSectionTime(60, 240)));
        })).given(busDelegate).getArrInfoByStop(anyString(), anyString(), anyInt());
        willAnswer(call -> throughSharedLimiter(() -> {
            positionCalls.incrementAndGet();
            return BusQueryResult.ok(List.of(position()));
        })).given(busDelegate).getBusPosByRtid(anyString());
        willAnswer(call -> {
            mlCalls.incrementAndGet();
            Thread.sleep(PROVIDER_LATENCY_MS);
            List<MlFeatureVector> vectors = call.getArgument(0);
            return vectors.stream()
                    .map(v -> new MlPrediction(v.requestId(), MlPredictionStatus.AVAILABLE,
                            300.0, "load-test"))
                    .toList();
        }).given(mlDelegate).predict(anyList());
    }

    /** 공용 슬롯을 잡고 provider 왕복 시간만큼 머문다. 못 잡으면 실제 어댑터와 같이 LIMITED. */
    private <T> BusQueryResult<T> throughSharedLimiter(java.util.function.Supplier<BusQueryResult<T>> call)
            throws InterruptedException {
        if (!sharedLimiter.tryAcquire(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            return BusQueryResult.limited();
        }
        try {
            Thread.sleep(PROVIDER_LATENCY_MS);
            return call.get();
        } finally {
            sharedLimiter.release();
        }
    }

    /**
     * 부하 도중 사용자 검색이 조회를 못 하는 비율을 잰다.
     *
     * <p>캐시에 걸리면 provider 를 안 거치므로 매번 다른 키로 부른다 — 검색이 실제로 슬롯을
     * 두고 경쟁하는 상황을 만든다.
     */
    private Thread startSearchLoad() {
        Thread thread = new Thread(() -> {
            int i = 0;
            while (!Thread.currentThread().isInterrupted()) {
                BusQueryResult<?> result =
                        searchBusPort.getArrInfoByStop("SEARCH-" + i++, "SEARCH-ROUTE", 1);
                searchTotal.incrementAndGet();
                if (result.outcome() == SearchTimeBusQueryPort.Outcome.LIMITED) {
                    searchLimited.incrementAndGet();
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "load-search");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private BusPositionInfo position() {
        return new BusPositionInfo(
                "veh-load", 60, 1, 500.0, 10000.0, "0", "sec-1",
                ZonedDateTime.now(SEOUL).format(DATA_TM), "서울70사1234", 0, 120, "S0",
                null, null, "0", "0", 1000.0, "S1", 2, null, 127.01, 37.51, "1");
    }

    private ThreadPoolTaskExecutor executor(int poolSize) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("load-eval-");
        executor.initialize();
        return executor;
    }

    /** 예정 시각이 지났는데 아직 평가되지 않은 여정 수. */
    private long backlog() {
        Long count = jdbc.queryForObject("""
                select count(*) from journey_session
                 where status = 'ACTIVE' and deleted_at is null
                   and next_evaluation_at is not null and next_evaluation_at <= now()
                """, Long.class);
        return count == null ? 0 : count;
    }

    private int activeConnections() {
        if (dataSource instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
            return hikari.getHikariPoolMXBean().getActiveConnections();
        }
        return -1;
    }

    private void print(JourneyLoadProbe.Report report, int journeys, int distinctStops,
                       int poolSize, Duration window, Duration prepareTook) {
        System.out.printf("""

                ── 여정 평가 부하 결과 ─────────────────────────────
                여정 수                  : %d
                서로 다른 관심(정류장)   : %d
                워커 풀                  : %d
                선점 배치 / tick 간격    : %d건 / %dms
                관측 창                  : %ds (준비 %.1fs)
                ---------------------------------------------------
                평가 완료                : %d건
                건너뜀·소유권 상실       : %s
                평가 지연 p50 / p95 / p99: %.2fs / %.2fs / %.2fs
                최대 지연                : %.2fs
                적체 시작 / 최대 / 종료  : %d / %d / %d
                동시 선점 겹침           : %d건
                외부 호출 도착/위치/모델 : %d / %d / %d
                DB 커넥션 최대           : %d
                워커 큐 최대             : %d
                검색 요청 LIMITED        : %d / %d건
                ───────────────────────────────────────────────────
                %n""",
                journeys, distinctStops, poolSize, properties.getBatchSize(), properties.getTickMs(),
                window.toSeconds(), prepareTook.toMillis() / 1000.0,
                report.completed(), report.incompleteByReason(),
                report.latencySeconds(50), report.latencySeconds(95), report.latencySeconds(99),
                report.maxLatencySeconds(),
                report.firstBacklog(), report.maxBacklog(), report.lastBacklog(),
                report.overlappingClaims(),
                arrivalCalls.get(), positionCalls.get(), mlCalls.get(),
                report.maxActiveConnections(), report.maxQueueSize(),
                searchLimited.get(), searchTotal.get());
    }

    private static int envInt(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }

    private static double envDouble(String name, double fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
    }
}
