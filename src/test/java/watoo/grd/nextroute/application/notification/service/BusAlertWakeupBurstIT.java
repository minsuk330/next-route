package watoo.grd.nextroute.application.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.bus.InMemoryBusRealtimeCache;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.config.TossMessengerProperties;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.notifination.BusAlertRelayLoop;
import watoo.grd.nextroute.testsupport.AbstractBusAlertOutboxPostgresIT;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 wake-up 배선의 200건 burst 성능:
 * dispatch tx commit → AFTER_COMMIT listener → waker → worker relay loop → SENT.
 *
 * <p>sweep을 60초로 두므로 테스트 제한시간 안의 발송은 event wake 경로로만 가능하다.
 * 외부 Toss는 호출하지 않고 설정된 지연의 counting stub을 사용한다.
 */
@DataJpaTest
@ActiveProfiles("worker")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        BusAlertDispatchService.class,
        BusAlertStateService.class,
        BusAlertOutboxEnqueueService.class,
        BusAlertOutboxStateService.class,
        BusAlertOutboxRelayService.class,
        RelayWaker.class,
        RelayWakeListener.class,
        BusAlertRelayLoop.class,
        BusAlertWakeupBurstIT.Config.class
})
class BusAlertWakeupBurstIT extends AbstractBusAlertOutboxPostgresIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @DynamicPropertySource
    static void wakeupProperties(DynamicPropertyRegistry registry) {
        int total = intEnv("BUS_ALERT_WAKEUP_BURST_TOTAL", 200);
        registry.add("notification.bus-arrival.enabled", () -> "true");
        registry.add("notification.bus-arrival.batch-size", () -> String.valueOf(total * 2));
        registry.add("notification.bus-arrival.outbox-relay-batch-size", () -> String.valueOf(total * 2));
        registry.add("notification.bus-arrival.outbox-relay-workers",
                () -> String.valueOf(intEnv("BUS_ALERT_WAKEUP_BURST_WORKERS", 32)));
        registry.add("notification.bus-arrival.outbox-sweep-interval-ms", () -> "60000");
        // relay 루프는 운영 기본 창(05:00~01:00) 밖에서 쉰다. 고정하지 않으면 01~05시에 돌린 실행만 발송 0건으로 실패한다.
        registry.add("notification.bus-arrival.active-hours.start", () -> "00:00");
        registry.add("notification.bus-arrival.active-hours.end", () -> "00:00");
    }

    @Autowired BusAlertDispatchService dispatchService;
    @Autowired BusAlertRelayLoop relayLoop;
    @Autowired BusArrivalAlertProperties properties;
    @Autowired CountingTossMessengerPort tossMessengerPort;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired Clock clock;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from bus_alert_notification");
        jdbcTemplate.update("delete from bus_arrival_alert");
        jdbcTemplate.update("delete from users");
        tossMessengerPort.reset();
    }

    @Test
    void afterCommitWakeupBurst_p50p95p99(TestReporter reporter) throws Exception {
        int total = intEnv("BUS_ALERT_WAKEUP_BURST_TOTAL", 200);
        int groups = intEnv("BUS_ALERT_WAKEUP_BURST_GROUPS", 40);
        seedDueAlerts(total, groups);
        assertThat(relayLoop.isRunning()).isTrue();

        LocalDateTime dueAt = LocalDateTime.now(clock);
        long started = System.nanoTime();
        dispatchService.dispatchDue();
        long dispatchReturnMs = elapsedMillis(started);

        boolean completed = awaitUntil(
                () -> count("bus_alert_notification", "SENT") == total,
                longEnv("BUS_ALERT_WAKEUP_BURST_TIMEOUT_MS", 15_000));
        long allSentMs = elapsedMillis(started);

        assertThat(completed).as("60초 sweep 전에 wake-up 경로로 전량 발송").isTrue();
        assertThat(count("bus_arrival_alert", "SENT")).isEqualTo(total);
        assertThat(count("bus_arrival_alert", "PROCESSING")).isZero();
        assertThat(count("bus_alert_notification", "PROCESSING")).isZero();
        assertThat(tossMessengerPort.calls()).isEqualTo(total);

        List<Long> latencies = sentLatenciesFrom(dueAt);
        assertThat(latencies).hasSize(total);
        long p50 = percentile(latencies, 0.50);
        long p95 = percentile(latencies, 0.95);
        long p99 = percentile(latencies, 0.99);
        long max = percentile(latencies, 1.0);

        Map<String, String> result = new LinkedHashMap<>();
        result.put("scenario", "after-commit-wakeup-burst");
        result.put("total", String.valueOf(total));
        result.put("groups", String.valueOf(groups));
        result.put("workers", String.valueOf(properties.getOutboxRelayWorkers()));
        result.put("tossLatencyMs",
                String.valueOf(longEnv("BUS_ALERT_WAKEUP_BURST_TOSS_LATENCY_MS", 20)));
        result.put("sweepIntervalMs", String.valueOf(properties.getOutboxSweepIntervalMs()));
        result.put("dispatchReturnMs", String.valueOf(dispatchReturnMs));
        result.put("allSentMs", String.valueOf(allSentMs));
        result.put("p50DueToSentMs", String.valueOf(p50));
        result.put("p95DueToSentMs", String.valueOf(p95));
        result.put("p99DueToSentMs", String.valueOf(p99));
        result.put("maxDueToSentMs", String.valueOf(max));
        reporter.publishEntry(result);
        System.out.println("[BusAlertWakeupBurstIT] " + result);
    }

    private void seedDueAlerts(int total, int groups) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Object[]> users = new ArrayList<>(total);
        List<Object[]> alerts = new ArrayList<>(total);
        for (int i = 1; i <= total; i++) {
            int group = (i - 1) % groups;
            users.add(new Object[]{(long) i, now, now, 1_000_000L + i});
            alerts.add(new Object[]{
                    (long) i, now, now, (long) i, "S" + group, "R" + group, 3,
                    "간선" + group, "정류장" + group, now.minusSeconds(1), 1,
                    "PENDING", now.plusMinutes(60), 0});
        }
        jdbcTemplate.batchUpdate(
                "insert into users (id, created_at, updated_at, toss_user_key) values (?,?,?,?)", users);
        jdbcTemplate.batchUpdate("""
                insert into bus_arrival_alert
                    (id, created_at, updated_at, user_id, stop_id, route_id, ord,
                     route_name, stop_name, user_eta, bus_arrival_minutes, status, expires_at, attempt_count)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, alerts);
    }

    private List<Long> sentLatenciesFrom(LocalDateTime dueAt) {
        List<LocalDateTime> sentTimes = jdbcTemplate.queryForList("""
                select sent_at
                from bus_alert_notification
                where status = 'SENT'
                order by sent_at
                """, LocalDateTime.class);
        List<Long> result = new ArrayList<>(sentTimes.size());
        for (LocalDateTime sentAt : sentTimes) {
            result.add(Duration.between(dueAt, sentAt).toMillis());
        }
        Collections.sort(result);
        return result;
    }

    private boolean awaitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private long count(String table, String status) {
        Long value = jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where status = ?", Long.class, status);
        return value == null ? 0 : value;
    }

    private long percentile(List<Long> sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static int intEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }

    private static long longEnv(String name, long defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Long.parseLong(value);
    }

    @TestConfiguration
    static class Config {
        @Bean
        BusArrivalAlertProperties busArrivalAlertProperties() {
            int total = intEnv("BUS_ALERT_WAKEUP_BURST_TOTAL", 200);
            BusArrivalAlertProperties p = new BusArrivalAlertProperties();
            p.setEnabled(true);
            p.setThresholdSeconds(120);
            p.setBatchSize(total * 2);
            p.setClaimTimeoutMinutes(3);
            p.setMaxAttempts(3);
            p.setPerUserCycleCap(Integer.MAX_VALUE);
            p.setBackoffSeconds(60);
            p.setOutboxRelayBatchSize(total * 2);
            p.setOutboxRelayWorkers(intEnv("BUS_ALERT_WAKEUP_BURST_WORKERS", 32));
            p.setOutboxSweepIntervalMs(60_000);
            p.setOutboxRelayMaxBatches(1_000);
            p.setOutboxDrainMaxMillis(30_000);
            return p;
        }

        @Bean
        TossMessengerProperties tossMessengerProperties() {
            TossMessengerProperties p = new TossMessengerProperties();
            p.setBusArrivalTemplateSetCode("BUS_ARRIVAL_WAKEUP_BURST_TEST");
            return p;
        }

        @Bean
        Clock clock() {
            return Clock.system(KST);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        CountingBusApiPort busApiPort() {
            return new CountingBusApiPort(0);
        }

        @Bean
        CountingTossMessengerPort tossMessengerPort() {
            return new CountingTossMessengerPort(
                    longEnv("BUS_ALERT_WAKEUP_BURST_TOSS_LATENCY_MS", 20));
        }

        @Bean
        InMemoryBusRealtimeCache busRealtimeCache() {
            return new InMemoryBusRealtimeCache();
        }
    }
}
