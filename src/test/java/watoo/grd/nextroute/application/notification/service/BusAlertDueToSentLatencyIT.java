package watoo.grd.nextroute.application.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import watoo.grd.nextroute.common.lock.AlwaysLeaderLeaseTestConfig;
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
import watoo.grd.nextroute.application.bus.InMemoryBusRealtimeCache;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.config.TossMessengerProperties;
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
 * due → SENT latency 측정용 IT.
 *
 * <p>이미 발송 조건을 만족한 alert 를 seed 하고, dispatch(enqueue) → relay(send) 후
 * 기준 dueAt 부터 outbox SENT 기록 시각까지의 p50/p95/p99 를 계산한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        BusAlertDispatchService.class,
        BusAlertStateService.class,
        BusAlertOutboxEnqueueService.class,
        BusAlertOutboxStateService.class,
        BusAlertOutboxRelayService.class,
        BusAlertDueToSentLatencyIT.Config.class,
        AlwaysLeaderLeaseTestConfig.class
})
class BusAlertDueToSentLatencyIT extends AbstractBusAlertOutboxPostgresIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @DynamicPropertySource
    static void dueToSentProperties(DynamicPropertyRegistry registry) {
        registry.add("notification.bus-arrival.batch-size",
                () -> String.valueOf(intEnv("BUS_ALERT_DUE_TO_SENT_TOTAL", 200) * 2));
        registry.add("notification.bus-arrival.outbox-relay-batch-size",
                () -> String.valueOf(intEnv("BUS_ALERT_DUE_TO_SENT_TOTAL", 200) * 2));
        registry.add("notification.bus-arrival.outbox-relay-workers",
                () -> String.valueOf(intEnv("BUS_ALERT_DUE_TO_SENT_WORKERS", 8)));
    }

    @Autowired BusAlertDispatchService dispatchService;
    @Autowired BusAlertOutboxRelayService relayService;
    @Autowired BusArrivalAlertProperties properties;
    @Autowired CountingTossMessengerPort tossMessengerPort;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from bus_alert_notification");
        jdbcTemplate.update("delete from bus_arrival_alert");
        jdbcTemplate.update("delete from users");
        tossMessengerPort.reset();
    }

    @Test
    void dueToSentLatency_p50p95p99(TestReporter reporter) {
        int total = intEnv("BUS_ALERT_DUE_TO_SENT_TOTAL", 200);
        int groups = intEnv("BUS_ALERT_DUE_TO_SENT_GROUPS", 40);
        seedDueAlerts(total, groups);

        LocalDateTime dueAt = LocalDateTime.now(clock);
        long dispatchMs = timed(() -> drain(
                "select count(*) from bus_arrival_alert where status = 'PENDING'",
                dispatchService::dispatchDue));
        long relayMs = timed(() -> drain(
                "select count(*) from bus_alert_notification where status = 'PENDING'",
                relayService::relayOnce));

        long alertSent = scalar("select count(*) from bus_arrival_alert where status = 'SENT'");
        long alertStuck = scalar("select count(*) from bus_arrival_alert where status = 'PROCESSING'");
        long outboxSent = scalar("select count(*) from bus_alert_notification where status = 'SENT'");
        long outboxStuck = scalar("select count(*) from bus_alert_notification where status = 'PROCESSING'");

        assertThat(alertSent).isEqualTo(total);
        assertThat(outboxSent).isEqualTo(total);
        assertThat(alertStuck).isZero();
        assertThat(outboxStuck).isZero();
        assertThat(tossMessengerPort.calls()).isEqualTo(total);

        List<Long> latencies = sentLatenciesFrom(dueAt);
        assertThat(latencies).hasSize(total);
        long p50 = percentile(latencies, 0.50);
        long p95 = percentile(latencies, 0.95);
        long p99 = percentile(latencies, 0.99);
        long max = percentile(latencies, 1.0);

        long maxP95Ms = longEnv("BUS_ALERT_DUE_TO_SENT_MAX_P95_MS", -1);
        if (maxP95Ms >= 0) {
            assertThat(p95).isLessThanOrEqualTo(maxP95Ms);
        }

        Map<String, String> report = new LinkedHashMap<>();
        report.put("scenario", "due-to-sent-latency");
        report.put("total", String.valueOf(total));
        report.put("groups", String.valueOf(groups));
        report.put("workers", String.valueOf(properties.getOutboxRelayWorkers()));
        report.put("tossLatencyMs", String.valueOf(longEnv("BUS_ALERT_DUE_TO_SENT_TOSS_LATENCY_MS", 20)));
        report.put("dispatchMs", String.valueOf(dispatchMs));
        report.put("relayMs", String.valueOf(relayMs));
        report.put("p50DueToSentMs", String.valueOf(p50));
        report.put("p95DueToSentMs", String.valueOf(p95));
        report.put("p99DueToSentMs", String.valueOf(p99));
        report.put("maxDueToSentMs", String.valueOf(max));
        reporter.publishEntry(report);
        System.out.println("[BusAlertDueToSentLatencyIT] " + report);
    }

    @Test
    void dueToSentLatency_intervalRelayBaseline(TestReporter reporter) {
        int total = intEnv("BUS_ALERT_DUE_TO_SENT_TOTAL", 200);
        int groups = intEnv("BUS_ALERT_DUE_TO_SENT_GROUPS", 40);
        long relayIntervalMs = longEnv("BUS_ALERT_INTERVAL_RELAY_MS", 1_000);
        seedDueAlerts(total, groups);

        LocalDateTime dueAt = LocalDateTime.now(clock);
        long dispatchMs = timed(() -> drain(
                "select count(*) from bus_arrival_alert where status = 'PENDING'",
                dispatchService::dispatchDue));

        // 기존 interval relay 모델: enqueue 이후 다음 scheduler tick 까지 구조적 대기.
        CountingBusApiPort.sleep(relayIntervalMs);

        long relayMs = timed(() -> drain(
                "select count(*) from bus_alert_notification where status = 'PENDING'",
                relayService::relayOnce));

        long alertSent = scalar("select count(*) from bus_arrival_alert where status = 'SENT'");
        long alertStuck = scalar("select count(*) from bus_arrival_alert where status = 'PROCESSING'");
        long outboxSent = scalar("select count(*) from bus_alert_notification where status = 'SENT'");
        long outboxStuck = scalar("select count(*) from bus_alert_notification where status = 'PROCESSING'");

        assertThat(alertSent).isEqualTo(total);
        assertThat(outboxSent).isEqualTo(total);
        assertThat(alertStuck).isZero();
        assertThat(outboxStuck).isZero();
        assertThat(tossMessengerPort.calls()).isEqualTo(total);

        List<Long> latencies = sentLatenciesFrom(dueAt);
        assertThat(latencies).hasSize(total);
        long p50 = percentile(latencies, 0.50);
        long p95 = percentile(latencies, 0.95);
        long p99 = percentile(latencies, 0.99);
        long max = percentile(latencies, 1.0);

        Map<String, String> report = new LinkedHashMap<>();
        report.put("scenario", "due-to-sent-interval-relay-baseline");
        report.put("total", String.valueOf(total));
        report.put("groups", String.valueOf(groups));
        report.put("workers", String.valueOf(properties.getOutboxRelayWorkers()));
        report.put("tossLatencyMs", String.valueOf(longEnv("BUS_ALERT_DUE_TO_SENT_TOSS_LATENCY_MS", 20)));
        report.put("relayIntervalMs", String.valueOf(relayIntervalMs));
        report.put("dispatchMs", String.valueOf(dispatchMs));
        report.put("relayMs", String.valueOf(relayMs));
        report.put("p50DueToSentMs", String.valueOf(p50));
        report.put("p95DueToSentMs", String.valueOf(p95));
        report.put("p99DueToSentMs", String.valueOf(p99));
        report.put("maxDueToSentMs", String.valueOf(max));
        reporter.publishEntry(report);
        System.out.println("[BusAlertDueToSentLatencyIT] " + report);
    }

    private void seedDueAlerts(int total, int groupCount) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Object[]> users = new ArrayList<>(total);
        List<Object[]> alerts = new ArrayList<>(total);
        for (int i = 1; i <= total; i++) {
            int g = (i - 1) % groupCount;
            users.add(new Object[]{(long) i, now, now, 1_000_000L + i});
            alerts.add(new Object[]{
                    (long) i, now, now, (long) i, "S" + g, "R" + g, 3,
                    "간선" + g, "정류장" + g, now.minusSeconds(1), 1,
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
        List<Long> latencies = new ArrayList<>(sentTimes.size());
        for (LocalDateTime sentAt : sentTimes) {
            latencies.add(Duration.between(dueAt, sentAt).toMillis());
        }
        Collections.sort(latencies);
        return latencies;
    }

    private long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    private long scalar(String sql) {
        Long v = jdbcTemplate.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }

    private long timed(Runnable r) {
        long start = System.nanoTime();
        r.run();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private void drain(String pendingSql, Runnable step) {
        while (scalar(pendingSql) > 0) {
            long before = scalar(pendingSql);
            step.run();
            if (scalar(pendingSql) == before) break;
        }
    }

    private static int intEnv(String name, int def) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? def : Integer.parseInt(v);
    }

    private static long longEnv(String name, long def) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? def : Long.parseLong(v);
    }

    @TestConfiguration
    static class Config {
        @Bean
        BusArrivalAlertProperties busArrivalAlertProperties() {
            int total = intEnv("BUS_ALERT_DUE_TO_SENT_TOTAL", 200);
            BusArrivalAlertProperties p = new BusArrivalAlertProperties();
            p.setThresholdSeconds(120);
            p.setBatchSize(total * 2);
            p.setClaimTimeoutMinutes(3);
            p.setMaxAttempts(3);
            p.setPerUserCycleCap(Integer.MAX_VALUE);
            p.setBackoffSeconds(60);
            p.setOutboxRelayBatchSize(total * 2);
            p.setOutboxRelayWorkers(intEnv("BUS_ALERT_DUE_TO_SENT_WORKERS", 8));
            return p;
        }

        @Bean
        TossMessengerProperties tossMessengerProperties() {
            TossMessengerProperties p = new TossMessengerProperties();
            p.setBusArrivalTemplateSetCode("BUS_ARRIVAL_DUE_TO_SENT_TEST");
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
            return new CountingBusApiPort(longEnv("BUS_ALERT_DUE_TO_SENT_BUS_API_LATENCY_MS", 0));
        }

        @Bean
        CountingTossMessengerPort tossMessengerPort() {
            return new CountingTossMessengerPort(longEnv("BUS_ALERT_DUE_TO_SENT_TOSS_LATENCY_MS", 20));
        }

        @Bean
        InMemoryBusRealtimeCache busRealtimeCache() {
            return new InMemoryBusRealtimeCache();
        }
    }
}
