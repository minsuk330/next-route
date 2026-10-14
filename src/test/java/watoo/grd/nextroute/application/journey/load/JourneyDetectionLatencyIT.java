package watoo.grd.nextroute.application.journey.load;

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
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.observability.JourneyEvaluationMetrics;
import watoo.grd.nextroute.application.journey.port.out.NotificationChannelResolver;
import watoo.grd.nextroute.application.journey.service.JourneyDueClaimService;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationService;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlFeatureVector;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPrediction;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPredictionStatus;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.route.service.PredictionSupportService;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;
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
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

/**
 * 외부 데이터가 바뀐 순간부터 평가기가 위험 판정을 내리기까지(감지 지연)를 잰다.
 *
 * <p>운영에서는 "공급자 데이터가 바뀐 정확한 순간"을 알 수 없다. 여기서는 공급자가 가짜라 그 순간
 * (t0)을 우리가 정한다. 워밍업으로 평가기를 정상 상태로 만든 뒤, t0 에 일부 여정의 모델 응답을
 * "놓칠 버스"로 바꾸고, 그 여정들이 처음 위험으로 판정된 시각을 기록한다.
 *
 * <p>감지 지연을 세 조각으로 나눈다.
 * <ul>
 *   <li><b>a. 다음 평가까지 기다림</b> — t0 뒤 첫 평가의 예정 시각 − t0 (예정이 이미 지났으면 0)</li>
 *   <li><b>b. 줄 서기·계산</b> — 그 첫 평가의 완료 시각 − 예정 시각</li>
 *   <li><b>c. 한 번 더 놓침</b> — 첫 평가가 바뀐 데이터를 못 보고(캐시) 다음 평가에서야 잡은 시간</li>
 * </ul>
 *
 * <p>외부 지연은 운영 Prometheus 7일 실측을 기본값으로 쓴다(서울 버스 평균 72.8ms, ML 13.8ms).
 * 운영 지표에 히스토그램이 없어 꼬리 분포는 모른다 — {@code JDL_SLOW_RATIO}·{@code JDL_SLOW_MS} 로
 * 가끔 튀는 호출을 따로 섞는다(운영 최대 3,971ms).
 *
 * <pre>
 * JOURNEY_DETECTION_LATENCY=true JDL_JOURNEYS=300 JDL_WARMUP_SEC=240 JDL_OBSERVE_SEC=240 \
 *   JDL_BUS_MS=73 JDL_ML_MS=14 JDL_OUT=build/detection.tsv \
 *   ./gradlew cleanTest test --tests '*JourneyDetectionLatencyIT'
 * </pre>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "ml.predictor.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=10"
})
@EnabledIfEnvironmentVariable(named = "JOURNEY_DETECTION_LATENCY", matches = "true")
@DisplayName("감지 지연 — 데이터가 바뀐 뒤 위험 판정까지")
class JourneyDetectionLatencyIT {

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

    /** 바뀐 뒤 모델 응답. 타고 있는 사람에게 1분 뒤 오는 차는 놓친 차다. */
    private static final int FLIPPED_MODEL_SECONDS = 60;
    /** 바뀌기 전 모델 응답 = 타는 시간 + 이만큼. 넉넉히 여유. */
    private static final int COMFORTABLE_MARGIN_SECONDS = 900;
    private static final DateTimeFormatter DATA_TM = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired JourneyDueClaimService claimService;
    @Autowired JourneyEvaluatorProperties properties;
    @Autowired JourneyEvaluationMetrics metrics;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("busSearchConcurrencyLimiter") Semaphore sharedLimiter;

    @SpyBean JourneyEvaluationService evaluationService;
    @MockBean SearchTimeBusAdapter busDelegate;
    @MockBean MlArrivalPredictorAdapter mlDelegate;
    @MockBean SeoulSubwayApiAdapter subwayDelegate;
    @MockBean PredictionSupportService supportService;
    @MockBean GongApiAdapter holidayCalendar;
    /** 알림 적재까지 보려면 수신 채널이 있어야 한다. 픽스처 사용자는 토큰이 없다. */
    @MockBean NotificationChannelResolver channelResolver;

    private final Random random = new Random(42);
    private final AtomicLong busCalls = new AtomicLong();
    private final AtomicLong mlCalls = new AtomicLong();
    private final Map<Long, List<Eval>> evals = new ConcurrentHashMap<>();
    private volatile boolean flipped;
    /** 여정 순번 → 그 여정의 데이터가 바뀌는 시각(epoch ms). 여정마다 다르게 흩뜨린다. */
    private final Map<Integer, Long> flipAtMs = new ConcurrentHashMap<>();

    private long busMs;
    private long mlMs;
    private double slowRatio;
    private long slowMs;
    private JourneyLoadFixture fixture;
    private JourneyLoadFixture.Prepared prepared;
    private JourneyLoadFixture.Spec spec;
    private int flipEvery;

    /** 평가 한 번. 시각은 서버 시계 기준 epoch ms. */
    record Eval(Instant dueAt, long startedMs, long completedMs, boolean risky, String result) {
    }

    @AfterEach
    void cleanUp() {
        if (fixture != null && prepared != null) {
            fixture.cleanUp(prepared);
        }
    }

    @Test
    @DisplayName("t0 에 일부 여정의 공급자 응답을 위험으로 바꾸고, 위험 판정까지 걸린 시간을 조각별로 남긴다")
    void measureDetectionLatency() throws Exception {
        int journeys = envInt("JDL_JOURNEYS", 300);
        flipEvery = envInt("JDL_FLIP_EVERY", 5);
        Duration warmup = Duration.ofSeconds(envInt("JDL_WARMUP_SEC", 240));
        Duration observe = Duration.ofSeconds(envInt("JDL_OBSERVE_SEC", 420));
        // 바뀌는 순간을 여정마다 이 창 안에 흩뜨린다. 한 순간(t0)에 몰아 바꾸면, 같은 때 시작해 같은
        // 간격으로 도는 여정들의 평가 위상과 t0 의 상대 위치가 결과를 정해 버린다(2026-10-14 진단:
        // 같은 부류가 워밍업 60초면 대기 p95 87초, 240초면 38초).
        Duration flipWindow = Duration.ofSeconds(envInt("JDL_FLIP_WINDOW_SEC", 180));
        busMs = envInt("JDL_BUS_MS", 73);
        mlMs = envInt("JDL_ML_MS", 14);
        slowRatio = envDouble("JDL_SLOW_RATIO", 0.0);
        slowMs = envInt("JDL_SLOW_MS", 4000);
        // 평가 간격이 다른 두 부류: 환승까지 15분 안(30초 간격) / 15분 넘음(120초, 값이 그대로면 최대 180초).
        // 측정 내내 부류가 바뀌지 않도록 길게 잡는다. 이미 위험한 여정은 바뀔 게 없어 대상이 아니다.
        int[] rides = parseRides(env("JDL_RIDES", "1080,2400"));

        givenProviders();
        recordEvaluations();

        fixture = new JourneyLoadFixture(jdbc);
        spec = new JourneyLoadFixture.Spec(journeys, journeys, Instant.now(), properties.interval(),
                0.0, JourneyLoadFixture.DEFAULT_RIDE_SECONDS, rides);
        prepared = fixture.prepare(spec);

        ThreadPoolTaskExecutor executor = executor(properties.getPoolSize());
        JourneyDueEvaluatorScheduler scheduler = new JourneyDueEvaluatorScheduler(
                claimService, evaluationService, metrics, properties, executor);
        AtomicBoolean running = new AtomicBoolean(true);
        Thread loop = new Thread(() -> {
            while (running.get()) {
                scheduler.run();
                try {
                    Thread.sleep(properties.getTickMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "detection-evaluator");
        loop.setDaemon(true);
        loop.start();

        Instant t0;
        try {
            Thread.sleep(warmup.toMillis());
            t0 = Instant.now();
            Random flipRandom = new Random(7);
            for (int i = 0; i < journeys; i++) {
                if (isFlipped(i)) {
                    flipAtMs.put(i, t0.toEpochMilli() + (long) (flipRandom.nextDouble() * flipWindow.toMillis()));
                }
            }
            flipped = true;
            Thread.sleep(observe.toMillis());
        } finally {
            running.set(false);
            loop.join(5_000);
            executor.shutdown();
            executor.getThreadPoolExecutor().awaitTermination(10, TimeUnit.SECONDS);
        }

        Report report = analyze(t0, rides);
        printIntervalsBeforeFlip(t0, rides);
        long enqueued = countRiskNotificationsAfter(t0);
        String conditions = String.format(
                "journeys=%d flipEvery=%d rides=%s bus=%dms ml=%dms slow=%.3f@%dms pool=%d permits=%d warmup=%ds flipWindow=%ds observe=%ds",
                journeys, flipEvery, env("JDL_RIDES", "1080,2400"), busMs, mlMs, slowRatio, slowMs,
                properties.getPoolSize(), properties.getProviderPermits(), warmup.toSeconds(),
                flipWindow.toSeconds(), observe.toSeconds());
        print(conditions, report, enqueued);
        append(conditions, report, enqueued);

        assertThat(report.total()).as("바뀐 여정이 0개면 측정이 의미 없다").isPositive();
    }

    // ── 분석 ────────────────────────────────────────────────────────────────

    /** 부류(타는 시간)별로 감지 지연과 그 조각을 모은다. */
    private Report analyze(Instant t0, int[] rides) {
        Map<Integer, Group> byRide = new TreeMap<>();
        for (int ride : rides) {
            byRide.put(ride, new Group());
        }
        int missed = 0;
        for (int i = 0; i < prepared.journeyIds().size(); i++) {
            if (!isFlipped(i)) {
                continue;
            }
            Group group = byRide.get(spec.rideSeconds(i));
            long t0Ms = flipAtMs.get(i);
            List<Eval> list = evals.getOrDefault(prepared.journeyIds().get(i), List.of()).stream()
                    .filter(e -> e.completedMs() >= t0Ms)
                    .sorted((x, y) -> Long.compare(x.completedMs(), y.completedMs()))
                    .toList();
            Eval first = list.isEmpty() ? null : list.get(0);
            Eval detect = list.stream().filter(Eval::risky).findFirst().orElse(null);
            if (first == null || detect == null) {
                group.undetected++;
                missed++;
                continue;
            }
            long dueMs = first.dueAt() == null ? first.startedMs() : first.dueAt().toEpochMilli();
            group.detection.add(detect.completedMs() - t0Ms);
            group.waitNext.add(Math.max(0, dueMs - t0Ms));
            group.queueCompute.add(first.completedMs() - Math.max(dueMs, t0Ms));
            group.extraCycle.add(detect.completedMs() - first.completedMs());
            if (detect != first) {
                group.missedFirst++;
            }
        }
        return new Report(byRide, missed, busCalls.get(), mlCalls.get());
    }

    static final class Group {
        final List<Long> detection = new ArrayList<>();
        final List<Long> waitNext = new ArrayList<>();
        final List<Long> queueCompute = new ArrayList<>();
        final List<Long> extraCycle = new ArrayList<>();
        int missedFirst;
        int undetected;
    }

    record Report(Map<Integer, Group> byRide, int undetected, long busCalls, long mlCalls) {
        int total() {
            return byRide.values().stream().mapToInt(g -> g.detection.size() + g.undetected).sum();
        }

        List<Long> allDetection() {
            List<Long> all = new ArrayList<>();
            byRide.values().forEach(g -> all.addAll(g.detection));
            return all;
        }
    }

    /**
     * 바뀌기 시작하기 전 4분 동안 부류별로 평가와 평가 사이 간격이 실제로 얼마였는지. 감지 지연의 a 조각(다음
     * 평가까지 기다림)이 왜 그 크기인지 설명하려면 간격 정책이 실제로 고른 값을 봐야 한다.
     */
    private void printIntervalsBeforeFlip(Instant t0, int[] rides) {
        long t0Ms = t0.toEpochMilli();
        Map<Integer, List<Long>> gaps = new TreeMap<>();
        for (int i = 0; i < prepared.journeyIds().size(); i++) {
            List<Eval> list = evals.getOrDefault(prepared.journeyIds().get(i), List.of()).stream()
                    .filter(e -> e.completedMs() < t0Ms && e.completedMs() >= t0Ms - 240_000)
                    .sorted((x, y) -> Long.compare(x.completedMs(), y.completedMs()))
                    .toList();
            for (int k = 1; k < list.size(); k++) {
                gaps.computeIfAbsent(spec.rideSeconds(i), r -> new ArrayList<>())
                        .add(list.get(k).completedMs() - list.get(k - 1).completedMs());
            }
        }
        gaps.forEach((ride, g) -> System.out.printf(
                "바뀌기 전 평가 간격 — 타는 %4ds: n=%d p10/p50/p90 = %s/%s/%s s%n",
                ride, g.size(), sec(pct(g, 10)), sec(pct(g, 50)), sec(pct(g, 90))));
    }

    private long countRiskNotificationsAfter(Instant t0) {
        Long count = jdbc.queryForObject("""
                select count(distinct journey_id) from journey_notification
                 where journey_id = any (?) and checkpoint like 'TRANSFER_%'
                """, Long.class, (Object) prepared.journeyIds().toArray(Long[]::new));
        return count == null ? 0 : count;
    }

    // ── 장치 ────────────────────────────────────────────────────────────────

    private boolean isFlipped(int index) {
        return index % flipEvery == 0;
    }

    /** 평가마다 예정 시각·시작·완료·위험 여부를 남긴다. */
    private void recordEvaluations() {
        willAnswer(call -> {
            JourneyDueClaim claim = call.getArgument(0);
            long startedMs = System.currentTimeMillis();
            JourneyEvaluationService.Report result = (JourneyEvaluationService.Report) call.callRealMethod();
            boolean risky = result.assessments().stream()
                    .map(TransferRiskAssessment::status)
                    .anyMatch(s -> s == TransferStatus.AT_RISK || s == TransferStatus.MISSED);
            evals.computeIfAbsent(claim.journeyId(), k -> new CopyOnWriteArrayList<>())
                    .add(new Eval(claim.dueAt(), startedMs, System.currentTimeMillis(), risky,
                            result.result().name()));
            return result;
        }).given(evaluationService).evaluate(any());
    }

    private void givenProviders() {
        given(supportService.support(anyString())).willReturn(PredictionSupportService.Support.SUPPORTED);
        given(channelResolver.resolve(anyLong())).willReturn(List.of(NotificationChannel.PUSH));
        willAnswer(call -> throughSharedLimiter(() ->
                BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalWithSectionTime(60, 240)))))
                .given(busDelegate).getArrInfoByStop(anyString(), anyString(), anyInt());
        willAnswer(call -> throughSharedLimiter(() -> BusQueryResult.ok(List.of(position()))))
                .given(busDelegate).getBusPosByRtid(anyString());
        willAnswer(call -> {
            mlCalls.incrementAndGet();
            Thread.sleep(mlMs);
            List<MlFeatureVector> vectors = call.getArgument(0);
            return vectors.stream()
                    .map(v -> new MlPrediction(v.requestId(), MlPredictionStatus.AVAILABLE,
                            (double) modelSecondsFor(v), "detection-test"))
                    .toList();
        }).given(mlDelegate).predict(anyList());
    }

    /** 노선 id 끝 번호 = 관심 번호 = 여정 순번(정류장이 전부 다르다). */
    private long modelSecondsFor(MlFeatureVector vector) {
        Object routeId = vector.features().get("route_id");
        if (spec == null || routeId == null || !routeId.toString().startsWith(JourneyLoadFixture.ROUTE_PREFIX)) {
            return 3_600;
        }
        int index = Integer.parseInt(routeId.toString().substring(JourneyLoadFixture.ROUTE_PREFIX.length()));
        Long flipAt = flipAtMs.get(index);
        if (flipped && flipAt != null && System.currentTimeMillis() >= flipAt) {
            return FLIPPED_MODEL_SECONDS;
        }
        return spec.rideSeconds(index) + COMFORTABLE_MARGIN_SECONDS;
    }

    private <T> BusQueryResult<T> throughSharedLimiter(java.util.function.Supplier<BusQueryResult<T>> call)
            throws InterruptedException {
        if (!sharedLimiter.tryAcquire(500, TimeUnit.MILLISECONDS)) {
            return BusQueryResult.limited();
        }
        try {
            busCalls.incrementAndGet();
            Thread.sleep(sampleBusLatency());
            return call.get();
        } finally {
            sharedLimiter.release();
        }
    }

    /** 평균은 운영 실측. 꼬리는 운영에 히스토그램이 없어 비율로 따로 섞는다. */
    private long sampleBusLatency() {
        synchronized (random) {
            if (slowRatio > 0 && random.nextDouble() < slowRatio) {
                return slowMs;
            }
            // 평균 busMs 근처로 ±40% 흔든다(분포 모양은 미측정).
            return Math.max(1, Math.round(busMs * (0.6 + 0.8 * random.nextDouble())));
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
        executor.setThreadNamePrefix("detect-eval-");
        executor.initialize();
        return executor;
    }

    // ── 출력 ────────────────────────────────────────────────────────────────

    private void print(String conditions, Report r, long enqueued) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n── 감지 지연 결과 ─────────────────────────────────\n")
                .append("조건            : ").append(conditions).append('\n')
                .append(String.format("바뀐 여정 / 못 잡음 : %d / %d   위험 알림 적재 여정: %d%n",
                        r.total(), r.undetected(), enqueued))
                .append(String.format("외부 호출 버스/ML   : %d / %d%n", r.busCalls(), r.mlCalls()))
                .append(String.format("전체 감지 지연 p50/p95/max : %s / %s / %s s%n",
                        sec(pct(r.allDetection(), 50)), sec(pct(r.allDetection(), 95)), sec(max(r.allDetection()))));
        r.byRide().forEach((ride, g) -> sb.append(String.format(
                "  타는 %4ds | n=%3d 못잡음=%d 첫평가놓침=%d | 감지 p50/p95/max %s/%s/%s s | "
                        + "a.다음평가대기 p50/p95 %s/%s | b.줄+계산 p50/p95 %s/%s | c.한번더 p50/p95 %s/%s%n",
                ride, g.detection.size(), g.undetected, g.missedFirst,
                sec(pct(g.detection, 50)), sec(pct(g.detection, 95)), sec(max(g.detection)),
                sec(pct(g.waitNext, 50)), sec(pct(g.waitNext, 95)),
                sec(pct(g.queueCompute, 50)), sec(pct(g.queueCompute, 95)),
                sec(pct(g.extraCycle, 50)), sec(pct(g.extraCycle, 95)))));
        sb.append("───────────────────────────────────────────────────\n");
        System.out.println(sb);
    }

    private void append(String conditions, Report r, long enqueued) throws IOException {
        String out = System.getenv("JDL_OUT");
        if (out == null || out.isBlank()) {
            return;
        }
        Path path = Path.of(out);
        if (!Files.exists(path)) {
            Files.writeString(path, String.join("\t", "at", "conditions", "ride", "n", "undetected",
                    "missed_first", "det_p50", "det_p95", "det_max", "wait_p50", "wait_p95",
                    "queue_p50", "queue_p95", "extra_p50", "extra_p95", "enqueued", "bus_calls", "ml_calls") + "\n");
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Group> e : r.byRide().entrySet()) {
            Group g = e.getValue();
            sb.append(String.join("\t", Instant.now().toString(), conditions, String.valueOf(e.getKey()),
                    String.valueOf(g.detection.size()), String.valueOf(g.undetected), String.valueOf(g.missedFirst),
                    sec(pct(g.detection, 50)), sec(pct(g.detection, 95)), sec(max(g.detection)),
                    sec(pct(g.waitNext, 50)), sec(pct(g.waitNext, 95)),
                    sec(pct(g.queueCompute, 50)), sec(pct(g.queueCompute, 95)),
                    sec(pct(g.extraCycle, 50)), sec(pct(g.extraCycle, 95)),
                    String.valueOf(enqueued), String.valueOf(r.busCalls()), String.valueOf(r.mlCalls())))
                    .append('\n');
        }
        Files.writeString(path, sb.toString(), StandardOpenOption.APPEND);
    }

    private static long pct(List<Long> values, int percentile) {
        if (values.isEmpty()) {
            return -1;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static long max(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).max().orElse(-1);
    }

    private static String sec(long ms) {
        return ms < 0 ? "-" : String.format("%.1f", ms / 1000.0);
    }

    private static int[] parseRides(String value) {
        return java.util.Arrays.stream(value.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
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
