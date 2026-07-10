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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dispatcher capacity smoke test.
 *
 * <p>외부 서울버스/Toss 호출은 fake 로 고정하고, 실제 Postgres 위에서
 * {@link BusAlertDispatchService#dispatchDue()} 단일 worker 처리량을 측정한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        BusAlertDispatchService.class,
        BusAlertStateService.class,
        BusAlertDispatcherLoadIT.LoadTestConfig.class
})
class BusAlertDispatcherLoadIT {

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
    @Autowired BusArrivalAlertProperties properties;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CountingBusApiPort busApiPort;
    @Autowired CountingTossMessengerPort tossMessengerPort;

    private long nextSyntheticId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from bus_arrival_alert");
        jdbcTemplate.update("delete from users");
        busApiPort.reset();
        tossMessengerPort.reset();
        properties.setBatchSize(intEnv("BUS_ALERT_LOAD_BATCH_SIZE", 200));
        nextSyntheticId = 1L;
    }

    @Test
    void singleCycle_processesAtMostConfiguredBatch(TestReporter reporter) {
        int batchSize = properties.getBatchSize();
        int totalAlerts = intEnv("BUS_ALERT_LOAD_SINGLE_TOTAL", batchSize * 2);
        int groupCount = intEnv("BUS_ALERT_LOAD_GROUPS", 20);
        seedPendingAlerts(totalAlerts, groupCount);

        long elapsedMs = timed(dispatchService::dispatchDue);

        long sent = statusCount("SENT");
        long pending = statusCount("PENDING");
        assertThat(sent).isEqualTo(Math.min(totalAlerts, batchSize));
        assertThat(pending).isEqualTo(totalAlerts - sent);
        assertThat(tossMessengerPort.calls()).isEqualTo(sent);
        assertThat(busApiPort.calls()).isLessThanOrEqualTo(Math.min(groupCount, batchSize));

        publish(reporter, "single-cycle", totalAlerts, groupCount, 1, sent, pending, elapsedMs);
    }

    @Test
    void repeatedCycles_drainsBacklogAndReportsThroughput(TestReporter reporter) {
        int totalAlerts = intEnv("BUS_ALERT_LOAD_TOTAL", 600);
        int groupCount = intEnv("BUS_ALERT_LOAD_GROUPS", 30);
        int expectedCycles = (int) Math.ceil(totalAlerts / (double) properties.getBatchSize());
        seedPendingAlerts(totalAlerts, groupCount);

        long start = System.nanoTime();
        int cycles = 0;
        while (statusCount("PENDING") > 0) {
            dispatchService.dispatchDue();
            cycles++;
            assertThat(cycles).isLessThanOrEqualTo(expectedCycles);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        long sent = statusCount("SENT");
        assertThat(sent).isEqualTo(totalAlerts);
        assertThat(statusCount("PENDING")).isZero();
        assertThat(tossMessengerPort.calls()).isEqualTo(totalAlerts);
        assertThat(cycles).isEqualTo(expectedCycles);

        publish(reporter, "drain-backlog", totalAlerts, groupCount, cycles, sent, 0, elapsedMs);
    }

    @Test
    void oneSecondPollingSimulation_reportsBacklogAndOverrun(TestReporter reporter) {
        int ticks = intEnv("BUS_ALERT_POLL_SIM_TICKS", 30);
        int newPerTick = intEnv("BUS_ALERT_POLL_SIM_NEW_PER_TICK", 10);
        int groupCount = intEnv("BUS_ALERT_POLL_SIM_GROUPS", 10);
        List<Long> durations = new ArrayList<>(ticks);

        for (int tick = 1; tick <= ticks; tick++) {
            appendPendingAlerts(newPerTick, groupCount, tick);
            long elapsedMs = timed(dispatchService::dispatchDue);
            durations.add(elapsedMs);
        }

        long generated = (long) ticks * newPerTick;
        long sent = statusCount("SENT");
        long pending = statusCount("PENDING");
        long overrunCount = durations.stream()
                .filter(duration -> duration > 1_000L)
                .count();
        double avgDurationMs = durations.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);
        long maxDurationMs = durations.stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);
        long p95DurationMs = percentile(durations, 0.95);
        long p99DurationMs = percentile(durations, 0.99);

        assertThat(sent + pending).isEqualTo(generated);
        assertThat(tossMessengerPort.calls()).isEqualTo(sent);
        assertThat(durations).hasSize(ticks);

        if (booleanEnv("BUS_ALERT_POLL_SIM_ASSERT_STABLE", false)) {
            assertThat(overrunCount).isZero();
            assertThat(pending).isZero();
            assertThat(p95DurationMs).isLessThan(1_000L);
        }

        publishPollingSimulation(
                reporter,
                ticks,
                newPerTick,
                groupCount,
                generated,
                sent,
                pending,
                avgDurationMs,
                maxDurationMs,
                p95DurationMs,
                p99DurationMs,
                overrunCount);
    }

    private void seedPendingAlerts(int totalAlerts, int groupCount) {
        List<Object[]> users = IntStream.rangeClosed(1, totalAlerts)
                .mapToObj(i -> new Object[]{
                        (long) i,
                        NOW,
                        NOW,
                        1_000_000L + i
                })
                .toList();
        jdbcTemplate.batchUpdate("""
                insert into users (id, created_at, updated_at, toss_user_key)
                values (?, ?, ?, ?)
                """, users);

        List<Object[]> alerts = IntStream.rangeClosed(1, totalAlerts)
                .mapToObj(i -> {
                    int group = (i - 1) % groupCount;
                    LocalDateTime rowTime = NOW.plusNanos(i * 1_000L);
                    return new Object[]{
                            (long) i,
                            rowTime,
                            rowTime,
                            (long) i,
                            "S" + group,
                            "R" + group,
                            (group % 20) + 1,
                            "간선" + group,
                            "정류장" + group,
                            NOW,
                            1,
                            "PENDING",
                            NOW.plusMinutes(60),
                            0
                    };
                })
                .toList();
        jdbcTemplate.batchUpdate("""
                insert into bus_arrival_alert
                    (id, created_at, updated_at, user_id, stop_id, route_id, ord,
                     route_name, stop_name, user_eta, bus_arrival_minutes, status,
                     expires_at, attempt_count)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, alerts);
    }

    private void appendPendingAlerts(int count, int groupCount, int tick) {
        long firstId = nextSyntheticId;
        List<Object[]> users = IntStream.range(0, count)
                .mapToObj(offset -> {
                    long id = firstId + offset;
                    return new Object[]{
                            id,
                            NOW,
                            NOW,
                            2_000_000L + id
                    };
                })
                .toList();
        jdbcTemplate.batchUpdate("""
                insert into users (id, created_at, updated_at, toss_user_key)
                values (?, ?, ?, ?)
                """, users);

        List<Object[]> alerts = IntStream.range(0, count)
                .mapToObj(offset -> {
                    long id = firstId + offset;
                    int group = (int) ((id - 1) % groupCount);
                    LocalDateTime rowTime = NOW.plusSeconds(tick).plusNanos(offset * 1_000L);
                    return new Object[]{
                            id,
                            rowTime,
                            rowTime,
                            id,
                            "S" + group,
                            "R" + group,
                            (group % 20) + 1,
                            "간선" + group,
                            "정류장" + group,
                            NOW,
                            1,
                            "PENDING",
                            NOW.plusMinutes(60),
                            0
                    };
                })
                .toList();
        jdbcTemplate.batchUpdate("""
                insert into bus_arrival_alert
                    (id, created_at, updated_at, user_id, stop_id, route_id, ord,
                     route_name, stop_name, user_eta, bus_arrival_minutes, status,
                     expires_at, attempt_count)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, alerts);
        nextSyntheticId += count;
    }

    private long statusCount(String status) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from bus_arrival_alert where status = ?",
                Long.class,
                status);
        return count == null ? 0 : count;
    }

    private long timed(Runnable runnable) {
        long start = System.nanoTime();
        runnable.run();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private void publish(TestReporter reporter, String scenario, int totalAlerts, int groupCount,
                         int cycles, long sent, long pending, long elapsedMs) {
        double alertsPerSec = elapsedMs == 0 ? sent : sent * 1000.0 / elapsedMs;
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("scenario", scenario);
        metrics.put("totalAlerts", String.valueOf(totalAlerts));
        metrics.put("groupCount", String.valueOf(groupCount));
        metrics.put("batchSize", String.valueOf(properties.getBatchSize()));
        metrics.put("cycles", String.valueOf(cycles));
        metrics.put("sent", String.valueOf(sent));
        metrics.put("pending", String.valueOf(pending));
        metrics.put("busApiCalls", String.valueOf(busApiPort.calls()));
        metrics.put("tossCalls", String.valueOf(tossMessengerPort.calls()));
        metrics.put("elapsedMs", String.valueOf(elapsedMs));
        metrics.put("alertsPerSec", String.format("%.2f", alertsPerSec));
        reporter.publishEntry(metrics);
        System.out.println("[BusAlertDispatcherLoadIT] " + metrics);
    }

    private void publishPollingSimulation(TestReporter reporter, int ticks, int newPerTick, int groupCount,
                                          long generated, long sent, long pending, double avgDurationMs,
                                          long maxDurationMs, long p95DurationMs, long p99DurationMs,
                                          long overrunCount) {
        double overrunRatio = ticks == 0 ? 0 : overrunCount * 100.0 / ticks;
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("scenario", "one-second-polling");
        metrics.put("ticks", String.valueOf(ticks));
        metrics.put("newPerTick", String.valueOf(newPerTick));
        metrics.put("generatedAlerts", String.valueOf(generated));
        metrics.put("sent", String.valueOf(sent));
        metrics.put("pendingBacklog", String.valueOf(pending));
        metrics.put("batchSize", String.valueOf(properties.getBatchSize()));
        metrics.put("groupCount", String.valueOf(groupCount));
        metrics.put("busApiLatencyMs", String.valueOf(longEnv("BUS_ALERT_LOAD_BUS_API_LATENCY_MS", 0)));
        metrics.put("tossLatencyMs", String.valueOf(longEnv("BUS_ALERT_LOAD_TOSS_LATENCY_MS", 0)));
        metrics.put("busApiCalls", String.valueOf(busApiPort.calls()));
        metrics.put("tossCalls", String.valueOf(tossMessengerPort.calls()));
        metrics.put("avgDurationMs", String.format("%.2f", avgDurationMs));
        metrics.put("maxDurationMs", String.valueOf(maxDurationMs));
        metrics.put("p95DurationMs", String.valueOf(p95DurationMs));
        metrics.put("p99DurationMs", String.valueOf(p99DurationMs));
        metrics.put("overrunCount", String.valueOf(overrunCount));
        metrics.put("overrunRatio", String.format("%.2f%%", overrunRatio));
        reporter.publishEntry(metrics);
        System.out.println("[BusAlertDispatcherLoadIT] " + metrics);
    }

    private long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) return 0L;
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
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
    static class LoadTestConfig {

        @Bean
        BusArrivalAlertProperties busArrivalAlertProperties() {
            BusArrivalAlertProperties props = new BusArrivalAlertProperties();
            props.setThresholdSeconds(120);
            props.setTtlMinutes(60);
            props.setBatchSize(intEnv("BUS_ALERT_LOAD_BATCH_SIZE", 200));
            props.setClaimTimeoutMinutes(3);
            props.setMaxAttempts(3);
            props.setPerUserCycleCap(5);
            props.setBackoffSeconds(60);
            return props;
        }

        @Bean
        TossMessengerProperties tossMessengerProperties() {
            TossMessengerProperties props = new TossMessengerProperties();
            props.setBusArrivalTemplateSetCode("BUS_ARRIVAL_LOAD_TEST");
            return props;
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
        }

        @Bean
        CountingBusApiPort busApiPort() {
            return new CountingBusApiPort(longEnv("BUS_ALERT_LOAD_BUS_API_LATENCY_MS", 0));
        }

        @Bean
        CountingTossMessengerPort tossMessengerPort() {
            return new CountingTossMessengerPort(longEnv("BUS_ALERT_LOAD_TOSS_LATENCY_MS", 0));
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
            throw new IllegalStateException("load-test fake latency interrupted", e);
        }
    }
}
