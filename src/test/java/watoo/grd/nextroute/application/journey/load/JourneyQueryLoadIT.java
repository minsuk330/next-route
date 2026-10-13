package watoo.grd.nextroute.application.journey.load;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import watoo.grd.nextroute.application.journey.dto.JourneyEtaView;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.observability.JourneyEvaluationMetrics;
import watoo.grd.nextroute.application.journey.port.in.JourneyQueryUseCase;
import watoo.grd.nextroute.application.journey.service.JourneyDueClaimService;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationService;
import watoo.grd.nextroute.application.journey.service.JourneySnapshotEtaComposer;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlFeatureVector;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPrediction;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPredictionStatus;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.route.service.PredictionSupportService;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey.JourneyDueEvaluatorScheduler;
import watoo.grd.nextroute.infrastructure.adapter.out.api.bus.SearchTimeBusAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.api.gong.GongApiAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.api.mlpredictor.MlArrivalPredictorAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.api.subway.SeoulSubwayApiAdapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

/**
 * 앱처럼 여정을 10초마다 조회하면서, 평가기를 끈 옛 경로(off)와 켠 새 경로(on)를 같은 조건으로 잰다.
 *
 * <p>묻는 것은 셋이다.
 * <ul>
 *   <li><b>조회가 빨라졌나</b> — 같은 조회의 응답 시간 p50/p95/p99, 오류</li>
 *   <li><b>빠른 대신 오래된 판단을 주나</b> — 판단 경과 = 응답 시각 − 판정 계산 시각. on 에만 있다.
 *       off 는 요청 안에서 계산하므로 응답에 계산 시각이 없다(null). 0초로 채우지 않는다</li>
 *   <li><b>판단 못 하는 경우가 늘었나</b> — 모름 비율과 원인(첫 평가 전·낡음·그 밖)</li>
 * </ul>
 *
 * <p><b>조회 한 번 = 컨트롤러가 하는 일 그대로</b>: 여정 읽기({@link JourneyQueryUseCase#get}) +
 * ETA 조립({@link JourneySnapshotEtaComposer#compose}). off/on 분기는 조립 안에 있다. 앱이 보내는
 * "이 번호 이후만"(204) 단축은 쓰지 않는다 — on 이 더 싸지는 쪽이라, 빼고 재면 on 에 불리한 쪽이다.
 *
 * <p>외부 공급자(버스 도착·위치, ML)는 mock 으로 바꾸고 왕복 지연을 env 로 준다. 버스 도착 캐시
 * (Redis 15초)와 single-flight 는 실제 것이 그대로 돈다 — 옛 경로의 꼬리가 캐시 적중률에 달려 있어서다.
 * Redis 는 실행마다 새 컨테이너라 캐시 초기 상태가 같다.
 *
 * <p>평소에는 돌지 않는다.
 * <pre>
 * JOURNEY_QUERY_LOAD=true JQL_MODE=on JQL_LATENCY_MS=500 JQL_JOURNEYS=1000 JQL_QUERIED=200 \
 *   JQL_INTEREST=100 JQL_NEAR_RATIO=0.2 JQL_WARMUP_SEC=40 JQL_WINDOW_SEC=120 \
 *   JQL_OUT=build/query-load.tsv ./gradlew test --tests '*JourneyQueryLoadIT'
 * </pre>
 * {@code JQL_INTEREST=0} 이면 여정마다 관심(정류장)이 전부 다르다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "ml.predictor.enabled=true",
        // 운영 기본값(Hikari 10). 시험 공통값 5 는 조회 스레드끼리 커넥션을 다투게 해 두 경로를 똑같이 누른다.
        "spring.datasource.hikari.maximum-pool-size=10"
})
@EnabledIfEnvironmentVariable(named = "JOURNEY_QUERY_LOAD", matches = "true")
@DisplayName("여정 조회 부하 — 평가기 off/on")
class JourneyQueryLoadIT {

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

    private static final Duration POLL = Duration.ofSeconds(10);
    private static final long SEED = 42;
    private static final DateTimeFormatter DATA_TM = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired JourneyQueryUseCase queryUseCase;
    @Autowired JourneySnapshotEtaComposer etaComposer;
    @Autowired JourneyDueClaimService claimService;
    @Autowired JourneyEvaluationService evaluationService;
    @Autowired JourneyEvaluatorProperties properties;
    @Autowired JourneyEvaluationMetrics metrics;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("busSearchConcurrencyLimiter") Semaphore sharedLimiter;

    @MockBean SearchTimeBusAdapter busDelegate;
    @MockBean MlArrivalPredictorAdapter mlDelegate;
    @MockBean SeoulSubwayApiAdapter subwayDelegate;
    @MockBean PredictionSupportService supportService;
    @MockBean GongApiAdapter holidayCalendar;

    private final AtomicLong providerCalls = new AtomicLong();
    private long latencyMs;
    private boolean enabledBefore;
    private JourneyLoadFixture fixture;
    private JourneyLoadFixture.Prepared prepared;

    @AfterEach
    void cleanUp() {
        properties.setEnabled(enabledBefore);
        if (fixture != null && prepared != null) {
            fixture.cleanUp(prepared);
        }
    }

    @Test
    @DisplayName("설정한 조건으로 조회를 돌리고 응답 시간·판단 경과·모름 비율을 남긴다")
    void runScenario() throws Exception {
        boolean on = !"off".equalsIgnoreCase(env("JQL_MODE", "on"));
        latencyMs = envInt("JQL_LATENCY_MS", 50);
        int journeys = envInt("JQL_JOURNEYS", 1_000);
        int queried = Math.min(journeys, envInt("JQL_QUERIED", 200));
        int interest = envInt("JQL_INTEREST", 100);
        int distinctStops = interest <= 0 ? journeys : Math.min(interest, journeys);
        double nearRatio = envDouble("JQL_NEAR_RATIO", 0.2);
        Duration warmup = Duration.ofSeconds(envInt("JQL_WARMUP_SEC", 40));
        Duration window = Duration.ofSeconds(envInt("JQL_WINDOW_SEC", 120));
        int poolSize = envInt("JQL_POOL", properties.getPoolSize());

        enabledBefore = properties.isEnabled();
        properties.setEnabled(on);
        givenProviders();

        fixture = new JourneyLoadFixture(jdbc);
        JourneyLoadFixture.Spec spec = new JourneyLoadFixture.Spec(
                journeys, distinctStops, Instant.now(), properties.interval(), nearRatio);
        prepared = fixture.prepare(spec);

        ThreadPoolTaskExecutor executor = null;
        AtomicBoolean evaluatorRunning = new AtomicBoolean(on);
        Thread evaluator = null;
        if (on) {
            executor = executor(poolSize);
            JourneyDueEvaluatorScheduler scheduler = new JourneyDueEvaluatorScheduler(
                    claimService, evaluationService, metrics, properties, executor);
            evaluator = startEvaluator(scheduler, evaluatorRunning);
        }

        Instant measureFrom = Instant.now().plus(warmup);
        Instant stopAt = measureFrom.plus(window);
        List<Sample> samples = Collections.synchronizedList(new ArrayList<>());
        AtomicLong errors = new AtomicLong();
        AtomicLong inFlightAtStop = new AtomicLong();
        long callsAtMeasureStart;

        Random random = new Random(SEED);
        // 여정마다 스레드 하나(앱 한 대). 조회가 늦으면 그 앱의 다음 조회도 밀린다.
        ExecutorService pollers = Executors.newFixedThreadPool(queried);
        try {
            for (int i = 0; i < queried; i++) {
                // 조회 대상은 여정 전체에 고르게 흩는다. 앞쪽만 고르면 만기·임박 분포가 치우친다.
                int index = (int) ((long) i * journeys / queried);
                long journeyId = prepared.journeyIds().get(index);
                long userId = prepared.userIds().get(index);
                boolean near = spec.isNear(index);
                long firstDelay = random.nextInt((int) POLL.toMillis());
                pollers.submit(() -> poll(userId, journeyId, near, firstDelay, measureFrom, stopAt,
                        samples, errors, inFlightAtStop));
            }
            sleepUntil(measureFrom);
            callsAtMeasureStart = providerCalls.get();
            sleepUntil(stopAt);
        } finally {
            pollers.shutdown();
            pollers.awaitTermination(30, TimeUnit.SECONDS);
            evaluatorRunning.set(false);
            if (evaluator != null) {
                evaluator.join(5_000);
            }
            if (executor != null) {
                executor.shutdown();
            }
        }
        long providerCallsInWindow = providerCalls.get() - callsAtMeasureStart;
        printIntervalsByNearness(spec);
        printSlowResponseTiming(samples, measureFrom);

        Report report = new Report(List.copyOf(samples), errors.get(), inFlightAtStop.get(),
                properties.getStaleAfterSeconds());
        String conditions = String.format(
                "mode=%s latency=%dms journeys=%d queried=%d interest=%s near=%.2f pool=%d permits=%d warmup=%ds window=%ds",
                on ? "on" : "off", latencyMs, journeys, queried,
                interest <= 0 ? "all-distinct" : String.valueOf(distinctStops), nearRatio, poolSize,
                properties.getProviderPermits(),
                warmup.toSeconds(), window.toSeconds());
        print(conditions, report, providerCallsInWindow, window);
        append(conditions, report, providerCallsInWindow);

        assertThat(report.count()).as("측정 창 안의 응답이 0건이면 아래 수치는 의미가 없다").isPositive();
    }

    /**
     * 여정 하나를 앱처럼 10초마다 조회한다(closed loop — 응답이 늦으면 다음 조회도 밀린다).
     *
     * <p>측정 창 밖의 응답은 버린다. 창이 끝날 때 아직 진행 중인 조회는 따로 센다 — 완료된 것만으로
     * 빠르다고 말하지 않기 위해서다.
     */
    private void poll(long userId, long journeyId, boolean near, long firstDelayMs,
                      Instant measureFrom, Instant stopAt, List<Sample> samples,
                      AtomicLong errors, AtomicLong inFlightAtStop) {
        try {
            Thread.sleep(firstDelayMs);
            while (Instant.now().isBefore(stopAt)) {
                Instant startedAt = Instant.now();
                long t0 = System.nanoTime();
                JourneyEtaView eta = null;
                boolean failed = false;
                try {
                    JourneySnapshot snapshot = queryUseCase.get(userId, journeyId).orElseThrow();
                    eta = etaComposer.compose(userId, snapshot).eta();
                } catch (Exception e) {
                    failed = true;
                }
                long tookMs = (System.nanoTime() - t0) / 1_000_000;
                Instant respondedAt = Instant.now();
                if (respondedAt.isAfter(stopAt)) {
                    inFlightAtStop.incrementAndGet();
                    return;
                }
                if (!startedAt.isBefore(measureFrom)) {
                    if (failed) {
                        errors.incrementAndGet();
                    } else {
                        samples.add(Sample.of(tookMs, respondedAt, eta, near));
                    }
                }
                long rest = POLL.toMillis() - tookMs;
                if (rest > 0) {
                    Thread.sleep(rest);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 실제 스케줄러를 tick 간격으로 돈다(배치 완료 대기·tick 간격까지 반영). */
    private Thread startEvaluator(JourneyDueEvaluatorScheduler scheduler, AtomicBoolean running) {
        Thread thread = new Thread(() -> {
            while (running.get()) {
                scheduler.run();
                try {
                    Thread.sleep(properties.getTickMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "load-evaluator");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    // ── 공급자 ──────────────────────────────────────────────────────────────

    private void givenProviders() {
        given(supportService.support(anyString()))
                .willReturn(PredictionSupportService.Support.SUPPORTED);
        // 실제 어댑터는 공용 Semaphore 를 잡은 뒤 provider 를 부른다. mock 으로 바꿨으므로 같은 절차를 재현한다.
        willAnswer(call -> throughSharedLimiter(() ->
                BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalWithSectionTime(60, 240)))))
                .given(busDelegate).getArrInfoByStop(anyString(), anyString(), anyInt());
        willAnswer(call -> throughSharedLimiter(() -> BusQueryResult.ok(List.of(position()))))
                .given(busDelegate).getBusPosByRtid(anyString());
        willAnswer(call -> {
            providerCalls.incrementAndGet();
            Thread.sleep(latencyMs);
            List<MlFeatureVector> vectors = call.getArgument(0);
            return vectors.stream()
                    .map(v -> new MlPrediction(v.requestId(), MlPredictionStatus.AVAILABLE, 300.0, "load-test"))
                    .toList();
        }).given(mlDelegate).predict(anyList());
    }

    private <T> BusQueryResult<T> throughSharedLimiter(java.util.function.Supplier<BusQueryResult<T>> call)
            throws InterruptedException {
        if (!sharedLimiter.tryAcquire(500, TimeUnit.MILLISECONDS)) {
            return BusQueryResult.limited();
        }
        try {
            providerCalls.incrementAndGet();
            Thread.sleep(latencyMs);
            return call.get();
        } finally {
            sharedLimiter.release();
        }
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

    /**
     * 실험이 끝난 시점에 여정마다 정해진 다음 계산 간격과 판정 상태를 임박/비임박으로 나눠 본다.
     *
     * <p>임박 여정의 판단 경과가 전체와 같게 나올 때 원인을 가른다. 둘 다 10초 간격이면 픽스처가 전원을
     * 위험으로 만든 것이고, 간격은 갈리는데 경과가 같으면 선점 대기가 간격 차이를 덮은 것이다.
     * 간격에는 흩뜨리기(최대 jitter 초)가 더해져 있다.
     */
    private void printIntervalsByNearness(JourneyLoadFixture.Spec spec) {
        java.util.Map<Long, Integer> indexOf = new java.util.HashMap<>();
        for (int i = 0; i < prepared.journeyIds().size(); i++) {
            indexOf.put(prepared.journeyIds().get(i), i);
        }
        java.util.Map<Boolean, List<Long>> gaps = new java.util.HashMap<>();
        java.util.Map<Boolean, java.util.Map<String, Integer>> statuses = new java.util.HashMap<>();
        org.springframework.jdbc.core.RowCallbackHandler handler = rs -> {
            boolean near = spec.isNear(indexOf.get(rs.getLong(1)));
            statuses.computeIfAbsent(near, k -> new java.util.TreeMap<>())
                    .merge(String.valueOf(rs.getString(2)), 1, Integer::sum);
            double gap = rs.getDouble(3);
            if (!rs.wasNull()) {
                gaps.computeIfAbsent(near, k -> new ArrayList<>()).add(Math.round(gap));
            }
        };
        jdbc.query("""
                select s.id, t.status, extract(epoch from (s.next_evaluation_at - t.calculated_at))
                  from journey_session s
                  join transfer_connection t on t.journey_id = s.id and t.transfer_index = 0
                 where s.id = any (?)
                """, handler, (Object) prepared.journeyIds().toArray(Long[]::new));
        for (boolean near : List.of(true, false)) {
            List<Long> sorted = new ArrayList<>(gaps.getOrDefault(near, List.of()));
            Collections.sort(sorted);
            System.out.printf("다음 계산 간격(%s) n=%d p10/p50/p90 = %s / %s / %s 초, 상태 %s%n",
                    near ? "임박" : "비임박", sorted.size(), at(sorted, 10), at(sorted, 50), at(sorted, 90),
                    statuses.getOrDefault(near, java.util.Map.of()));
        }
    }

    /** p99 이상으로 느린 응답이 창의 어느 구간에 몰렸는지(10초 단위). 초반에 몰리면 예열, 고르면 상시 경합이다. */
    private void printSlowResponseTiming(List<Sample> samples, Instant measureFrom) {
        List<Long> took = samples.stream().map(Sample::tookMs).sorted().toList();
        if (took.isEmpty()) {
            return;
        }
        long threshold = took.get(Math.max(0, (int) Math.ceil(0.99 * took.size()) - 1));
        java.util.Map<Long, Integer> buckets = new java.util.TreeMap<>();
        samples.stream().filter(s -> s.tookMs() >= threshold).forEach(s -> buckets.merge(
                Duration.between(measureFrom, s.respondedAt()).toSeconds() / 10 * 10, 1, Integer::sum));
        System.out.printf("p99 이상(%dms+) 응답 %d건의 시각 분포(창 시작 후 초 → 건수): %s%n",
                threshold, buckets.values().stream().mapToInt(Integer::intValue).sum(), buckets);
    }

    private static String at(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return "-";
        }
        return String.valueOf(sorted.get(Math.max(0, (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1)));
    }

    // ── 기록 ────────────────────────────────────────────────────────────────

    /**
     * 응답 하나.
     *
     * @param evaluatedAt 판정 계산 시각. off 는 null(요청 안에서 방금 계산했다)
     * @param status      다음 환승 판정. 응답에 환승이 없으면 null
     */
    record Sample(long tookMs, Instant respondedAt, Instant evaluatedAt, TransferStatus status, boolean near) {

        static Sample of(long tookMs, Instant respondedAt, JourneyEtaView eta, boolean near) {
            Instant evaluatedAt = eta == null ? null : eta.evaluatedAt();
            TransferStatus status = eta == null || eta.activeTransfer() == null
                    ? null : eta.activeTransfer().status();
            return new Sample(tookMs, respondedAt, evaluatedAt, status, near);
        }

        boolean unknown() {
            return status == null || status == TransferStatus.UNKNOWN;
        }

        /** 판단 경과(초). 계산 시각이 없으면 -1. */
        double ageSeconds() {
            return evaluatedAt == null ? -1 : Duration.between(evaluatedAt, respondedAt).toMillis() / 1000.0;
        }
    }

    record Report(List<Sample> samples, long errors, long inFlightAtStop, long staleAfterSeconds) {

        int count() {
            return samples.size();
        }

        double latencyMs(int percentile) {
            return percentile(samples.stream().map(s -> (double) s.tookMs()).toList(), percentile);
        }

        double maxLatencyMs() {
            return samples.stream().mapToLong(Sample::tookMs).max().orElse(0);
        }

        long withoutEvaluatedAt() {
            return samples.stream().filter(s -> s.evaluatedAt() == null).count();
        }

        double ageSeconds(int percentile, boolean nearOnly) {
            return percentile(samples.stream()
                    .filter(s -> s.evaluatedAt() != null && (!nearOnly || s.near()))
                    .map(Sample::ageSeconds).toList(), percentile);
        }

        long overStale() {
            return samples.stream().filter(s -> s.ageSeconds() > staleAfterSeconds).count();
        }

        long unknown() {
            return samples.stream().filter(Sample::unknown).count();
        }

        /** 모름의 원인: 첫 평가 전(계산 시각 없음, on 일 때만 의미) */
        long unknownNoEvaluation() {
            return samples.stream().filter(s -> s.unknown() && s.evaluatedAt() == null).count();
        }

        /** 모름의 원인: 낡음(계산 시각이 기준보다 오래됨) */
        long unknownStale() {
            return samples.stream()
                    .filter(s -> s.unknown() && s.evaluatedAt() != null && s.ageSeconds() > staleAfterSeconds)
                    .count();
        }

        long unknownOther() {
            return unknown() - unknownNoEvaluation() - unknownStale();
        }

        private static double percentile(List<Double> values, int percentile) {
            if (values.isEmpty()) {
                return Double.NaN;
            }
            List<Double> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }
    }

    private void print(String conditions, Report r, long providerCallsInWindow, Duration window) {
        System.out.printf("""

                ── 여정 조회 부하 결과 ─────────────────────────────
                조건                       : %s
                머신                       : %s, %d cores
                ---------------------------------------------------
                응답 수 / 오류 / 창 끝 진행 중: %d / %d / %d
                응답 시간 p50/p95/p99/max  : %.0f / %.0f / %.0f / %.0f ms
                계산 시각 없는 응답        : %d
                판단 경과 p50/p95/p99      : %.1f / %.1f / %.1f s
                판단 경과(임박) p95        : %.1f s
                낡음 기준(%ds) 초과        : %d
                모름 전체 / 비율           : %d / %.1f%%
                  첫 평가 전 / 낡음 / 그 밖 : %d / %d / %d
                외부 호출(창 안)           : %d (%.1f/s)
                ───────────────────────────────────────────────────
                %n""",
                conditions, System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors(),
                r.count(), r.errors(), r.inFlightAtStop(),
                r.latencyMs(50), r.latencyMs(95), r.latencyMs(99), r.maxLatencyMs(),
                r.withoutEvaluatedAt(),
                r.ageSeconds(50, false), r.ageSeconds(95, false), r.ageSeconds(99, false),
                r.ageSeconds(95, true),
                r.staleAfterSeconds(), r.overStale(),
                r.unknown(), r.count() == 0 ? 0 : 100.0 * r.unknown() / r.count(),
                r.unknownNoEvaluation(), r.unknownStale(), r.unknownOther(),
                providerCallsInWindow, providerCallsInWindow / (double) window.toSeconds());
    }

    /** 실행마다 한 줄씩 쌓는다. 표로 옮기기 쉽게 탭으로 나눈다. */
    private void append(String conditions, Report r, long providerCallsInWindow) throws IOException {
        String out = System.getenv("JQL_OUT");
        if (out == null || out.isBlank()) {
            return;
        }
        Path path = Path.of(out);
        if (!Files.exists(path)) {
            Files.writeString(path, String.join("\t", "at", "conditions", "count", "errors", "inflight",
                    "p50ms", "p95ms", "p99ms", "maxms", "no_eval", "age_p50", "age_p95", "age_p99",
                    "age_near_p95", "over_stale", "unknown", "unk_no_eval", "unk_stale", "unk_other",
                    "provider_calls") + "\n");
        }
        Files.writeString(path, String.join("\t",
                Instant.now().toString(), conditions, String.valueOf(r.count()), String.valueOf(r.errors()),
                String.valueOf(r.inFlightAtStop()),
                fmt(r.latencyMs(50)), fmt(r.latencyMs(95)), fmt(r.latencyMs(99)), fmt(r.maxLatencyMs()),
                String.valueOf(r.withoutEvaluatedAt()),
                fmt(r.ageSeconds(50, false)), fmt(r.ageSeconds(95, false)), fmt(r.ageSeconds(99, false)),
                fmt(r.ageSeconds(95, true)), String.valueOf(r.overStale()),
                String.valueOf(r.unknown()), String.valueOf(r.unknownNoEvaluation()),
                String.valueOf(r.unknownStale()), String.valueOf(r.unknownOther()),
                String.valueOf(providerCallsInWindow)) + "\n", StandardOpenOption.APPEND);
    }

    private static String fmt(double value) {
        return Double.isNaN(value) ? "" : String.format("%.1f", value);
    }

    private static void sleepUntil(Instant at) throws InterruptedException {
        long millis = Duration.between(Instant.now(), at).toMillis();
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int envInt(String name, int fallback) {
        return Integer.parseInt(env(name, String.valueOf(fallback)));
    }

    private static double envDouble(String name, double fallback) {
        return Double.parseDouble(env(name, String.valueOf(fallback)));
    }
}
