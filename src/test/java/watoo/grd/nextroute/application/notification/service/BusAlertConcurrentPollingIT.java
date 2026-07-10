package watoo.grd.nextroute.application.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.application.bus.BusArrivalInfoFixtures;
import watoo.grd.nextroute.application.bus.dto.*;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.config.TossMessengerProperties;
import watoo.grd.nextroute.application.notification.port.out.TossMessengerPort;
import watoo.grd.nextroute.domain.notification.entity.AlertStatus;
import watoo.grd.nextroute.domain.notification.entity.BusArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.BusArrivalAlertRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent polling characterization.
 *
 * <p>현재 read-then-write claim 의 race window 를 테스트 전용 state service 로 넓혀,
 * 여러 worker 가 같은 PENDING alert 를 동시에 발송할 수 있는지 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        BusAlertDispatchService.class,
        BusAlertConcurrentPollingIT.ConcurrentPollingConfig.class
})
class BusAlertConcurrentPollingIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(Instant.parse("2026-06-23T03:00:00Z"), KST);

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
                    .withInitScript("db/it/bus-alert-it-schema.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired BusAlertDispatchService dispatchService;
    @Autowired RaceAmplifyingBusAlertStateService stateService;
    @Autowired CountingBusApiPort busApiPort;
    @Autowired CountingTossMessengerPort tossMessengerPort;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from bus_arrival_alert");
        jdbcTemplate.update("delete from users");
        stateService.reset();
        busApiPort.reset();
        tossMessengerPort.reset();
    }

    @Test
    void concurrentDispatchDue_reportsDuplicateSendRisk(TestReporter reporter) throws Exception {
        seedSinglePendingAlert();

        int workers = intEnv("BUS_ALERT_CONCURRENT_WORKERS", 2);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = java.util.stream.IntStream.range(0, workers)
                    .mapToObj(i -> executor.submit((Callable<Void>) () -> {
                        await(start);
                        dispatchService.dispatchDue();
                        return null;
                    }))
                    .toList();

            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        int tossCalls = tossMessengerPort.calls();
        publish(reporter, workers, tossCalls);

        assertThat(stateService.pendingReads()).isEqualTo(workers);
        assertThat(tossCalls).isGreaterThanOrEqualTo(1);
        assertThat(statusCount("SENT")).isEqualTo(1);

        if (booleanEnv("BUS_ALERT_CONCURRENT_ASSERT_DUPLICATE", false)) {
            assertThat(tossCalls).isGreaterThan(1);
        }
    }

    private void seedSinglePendingAlert() {
        jdbcTemplate.update("""
                insert into users (id, created_at, updated_at, toss_user_key)
                values (?, ?, ?, ?)
                """, 1L, NOW, NOW, 1_000_001L);
        jdbcTemplate.update("""
                insert into bus_arrival_alert
                    (id, created_at, updated_at, user_id, stop_id, route_id, ord,
                     route_name, stop_name, user_eta, bus_arrival_minutes, status,
                     expires_at, attempt_count)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                1L,
                NOW,
                NOW,
                1L,
                "S1",
                "R1",
                3,
                "간선143",
                "강남역",
                NOW,
                1,
                "PENDING",
                NOW.plusMinutes(60),
                0);
    }

    private long statusCount(String status) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from bus_arrival_alert where status = ?",
                Long.class,
                status);
        return count == null ? 0 : count;
    }

    private void publish(TestReporter reporter, int workers, int tossCalls) {
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("scenario", "concurrent-dispatch");
        metrics.put("workers", String.valueOf(workers));
        metrics.put("pendingReads", String.valueOf(stateService.pendingReads()));
        metrics.put("claimSuccesses", String.valueOf(stateService.claimSuccesses()));
        metrics.put("busApiCalls", String.valueOf(busApiPort.calls()));
        metrics.put("tossCalls", String.valueOf(tossCalls));
        metrics.put("duplicateSends", String.valueOf(Math.max(0, tossCalls - 1)));
        reporter.publishEntry(metrics);
        System.out.println("[BusAlertConcurrentPollingIT] " + metrics);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for concurrent start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", e);
        }
    }

    private static int intEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return defaultValue;
        return Integer.parseInt(value);
    }

    private static long longEnv(String name, long defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return defaultValue;
        return Long.parseLong(value);
    }

    private static boolean booleanEnv(String name, boolean defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return defaultValue;
        return Boolean.parseBoolean(value);
    }

    @TestConfiguration
    static class ConcurrentPollingConfig {

        @Bean
        BusArrivalAlertProperties busArrivalAlertProperties() {
            BusArrivalAlertProperties props = new BusArrivalAlertProperties();
            props.setThresholdSeconds(120);
            props.setTtlMinutes(60);
            props.setBatchSize(200);
            props.setClaimTimeoutMinutes(3);
            props.setMaxAttempts(3);
            props.setPerUserCycleCap(5);
            props.setBackoffSeconds(60);
            return props;
        }

        @Bean
        TossMessengerProperties tossMessengerProperties() {
            TossMessengerProperties props = new TossMessengerProperties();
            props.setBusArrivalTemplateSetCode("BUS_ARRIVAL_CONCURRENT_TEST");
            return props;
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
        }

        @Bean
        RaceAmplifyingBusAlertStateService busAlertStateService(
                BusArrivalAlertRepository repository,
                BusArrivalAlertProperties properties,
                Clock clock) {
            return new RaceAmplifyingBusAlertStateService(repository, properties, clock);
        }

        @Bean
        CountingBusApiPort busApiPort() {
            return new CountingBusApiPort(longEnv("BUS_ALERT_CONCURRENT_BUS_API_LATENCY_MS", 0));
        }

        @Bean
        CountingTossMessengerPort tossMessengerPort() {
            return new CountingTossMessengerPort(longEnv("BUS_ALERT_CONCURRENT_TOSS_LATENCY_MS", 0));
        }
    }

    static class RaceAmplifyingBusAlertStateService extends BusAlertStateService {
        private final BusArrivalAlertRepository alertRepository;
        private final Clock clock;
        private final CountDownLatch pendingReadLatch =
                new CountDownLatch(intEnv("BUS_ALERT_CONCURRENT_WORKERS", 2));
        private final AtomicInteger pendingReads = new AtomicInteger();
        private final AtomicInteger claimSuccesses = new AtomicInteger();
        private final long claimDelayMs = longEnv("BUS_ALERT_CONCURRENT_CLAIM_DELAY_MS", 50);

        RaceAmplifyingBusAlertStateService(BusArrivalAlertRepository alertRepository,
                                           BusArrivalAlertProperties properties,
                                           Clock clock) {
            super(alertRepository, properties, clock);
            this.alertRepository = alertRepository;
            this.clock = clock;
        }

        @Override
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public boolean claim(Long alertId) {
            BusArrivalAlert alert = alertRepository.findById(alertId).orElse(null);
            if (alert == null || alert.getStatus() != AlertStatus.PENDING) {
                return false;
            }
            pendingReads.incrementAndGet();
            pendingReadLatch.countDown();
            await(pendingReadLatch);
            sleep(claimDelayMs);
            alert.markProcessing(LocalDateTime.now(clock));
            claimSuccesses.incrementAndGet();
            return true;
        }

        int pendingReads() {
            return pendingReads.get();
        }

        int claimSuccesses() {
            return claimSuccesses.get();
        }

        void reset() {
            pendingReads.set(0);
            claimSuccesses.set(0);
        }
    }

    static class CountingBusApiPort implements BusApiPort {
        private final AtomicInteger calls = new AtomicInteger();
        private final long latencyMs;

        CountingBusApiPort(long latencyMs) {
            this.latencyMs = latencyMs;
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }

        @Override
        public List<BusArrivalInfo> getArrInfoByStop(String stopId, String routeId, String ord) {
            calls.incrementAndGet();
            sleep(latencyMs);
            return List.of(BusArrivalInfoFixtures.arrivalWithSectionTime(60, null));
        }

        @Override
        public List<BusRouteInfo> getBusRouteList(String searchKeyword) {
            return Collections.emptyList();
        }

        @Override
        public List<BusRouteInfo> getRouteInfo(String busRouteId) {
            return Collections.emptyList();
        }

        @Override
        public List<BusRouteStopInfo> getStationByRoute(String busRouteId) {
            return Collections.emptyList();
        }

        @Override
        public List<BusArrivalInfo> getArrInfoByRouteAll(String busRouteId) {
            return Collections.emptyList();
        }

        @Override
        public List<BusPositionInfo> getBusPosByRtid(String busRouteId) {
            return Collections.emptyList();
        }

        @Override
        public List<String> getBusRouteIds() {
            return Collections.emptyList();
        }

        @Override
        public BusRidershipFetchResult getBusRidershipByMonth(String month, int pageSize) {
            return new BusRidershipFetchResult(month, 0, Collections.emptyList());
        }
    }

    static class CountingTossMessengerPort implements TossMessengerPort {
        private final AtomicInteger calls = new AtomicInteger();
        private final long latencyMs;

        CountingTossMessengerPort(long latencyMs) {
            this.latencyMs = latencyMs;
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }

        @Override
        public void sendMessage(long tossUserKey, String templateSetCode, Map<String, Object> context) {
            calls.incrementAndGet();
            sleep(latencyMs);
        }
    }

    private static void sleep(long latencyMs) {
        if (latencyMs <= 0) return;
        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test latency interrupted", e);
        }
    }
}
