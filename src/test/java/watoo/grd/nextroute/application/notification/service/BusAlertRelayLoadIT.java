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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.application.bus.InMemoryBusRealtimeCache;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B. relay 부하: worker 수 × mock Toss 지연별 실제 발송 처리량을 측정한다.
 * <p>검증: 중복 send 0(send 수 = backlog), backlog 소진(PENDING→0, 전량 SENT), 완료지연 p95 보고.
 * claim SKIP LOCKED 로 병렬 워커가 같은 행을 두 번 보내지 않음. 규모/지연은 env 로 조정.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        BusAlertDispatchService.class,
        BusAlertStateService.class,
        BusAlertOutboxEnqueueService.class,
        BusAlertOutboxStateService.class,
        BusAlertRelayLoadIT.Config.class,
        AlwaysLeaderLeaseTestConfig.class
})
class BusAlertRelayLoadIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(Instant.parse("2026-06-23T03:00:00Z"), KST);

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
                    .withInitScript("db/it/bus-alert-outbox-it-schema.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired BusAlertDispatchService dispatchService;
    @Autowired BusAlertOutboxStateService outboxStateService;
    @Autowired BusArrivalAlertProperties properties;
    @Autowired TossMessengerProperties messengerProperties;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbcTemplate;

    private int backlog;
    private long latencyMs;

    @BeforeEach
    void setUp() {
        backlog = intEnv("BUS_ALERT_RELAY_LOAD_BACKLOG", 96);
        latencyMs = longEnv("BUS_ALERT_RELAY_LOAD_TOSS_LATENCY_MS", 20);
        properties.setOutboxRelayBatchSize(backlog * 2);
    }

    @Test
    void relayThroughput_byWorkers_noDuplicateSend_drainsBacklog(TestReporter reporter) {
        int[] workerConfigs = {1, 4, 8};
        for (int workers : workerConfigs) {
            resetAndSeed();

            RecordingTossPort toss = new RecordingTossPort(latencyMs);
            BusAlertOutboxRelayService relay = relay(toss, workers);
            toss.begin();
            long elapsedMs = timed(relay::relayOnce);
            relay.shutdown();

            long sent = scalar("select count(*) from bus_alert_notification where status = 'SENT'");
            long pending = scalar("select count(*) from bus_alert_notification where status = 'PENDING'");

            // 중복 send 0(정확히 backlog 회), backlog 소진.
            assertThat(toss.calls()).isEqualTo(backlog);
            assertThat(sent).isEqualTo(backlog);
            assertThat(pending).isZero();

            double perSec = elapsedMs == 0 ? backlog : backlog * 1000.0 / elapsedMs;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("scenario", "relay-load");
            m.put("workers", String.valueOf(workers));
            m.put("backlog", String.valueOf(backlog));
            m.put("tossLatencyMs", String.valueOf(latencyMs));
            m.put("elapsedMs", String.valueOf(elapsedMs));
            m.put("sendPerSec", String.format("%.1f", perSec));
            m.put("p95CompletionMs", String.valueOf(toss.percentile(0.95)));
            m.put("maxCompletionMs", String.valueOf(toss.percentile(1.0)));
            reporter.publishEntry(m);
            System.out.println("[BusAlertRelayLoadIT] " + m);
        }
    }

    /** backlog 재구성: N PENDING 구독 → dispatchDue 로 N outbox PENDING 인계. */
    private void resetAndSeed() {
        jdbcTemplate.update("delete from bus_alert_notification");
        jdbcTemplate.update("delete from bus_arrival_alert");
        jdbcTemplate.update("delete from users");
        List<Object[]> users = new ArrayList<>(backlog);
        List<Object[]> alerts = new ArrayList<>(backlog);
        for (int i = 1; i <= backlog; i++) {
            users.add(new Object[]{(long) i, NOW, NOW, 1_000_000L + i});
            alerts.add(new Object[]{
                    (long) i, NOW, NOW, (long) i, "S" + i, "R" + i, 3, "간선" + i, "정류장" + i,
                    NOW, 1, "PENDING", NOW.plusMinutes(60), 0});
        }
        jdbcTemplate.batchUpdate(
                "insert into users (id, created_at, updated_at, toss_user_key) values (?,?,?,?)", users);
        jdbcTemplate.batchUpdate("""
                insert into bus_arrival_alert
                    (id, created_at, updated_at, user_id, stop_id, route_id, ord,
                     route_name, stop_name, user_eta, bus_arrival_minutes, status, expires_at, attempt_count)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, alerts);
        // batchSize 무관하게 backlog 전량 enqueue(사이클 반복 소진).
        while (scalar("select count(*) from bus_arrival_alert where status = 'PENDING'") > 0) {
            long before = scalar("select count(*) from bus_arrival_alert where status = 'PENDING'");
            dispatchService.dispatchDue();
            if (scalar("select count(*) from bus_arrival_alert where status = 'PENDING'") == before) break;
        }
        assertThat(scalar("select count(*) from bus_alert_notification where status = 'PENDING'"))
                .isEqualTo(backlog);
    }

    private BusAlertOutboxRelayService relay(TossMessengerPort toss, int workers) {
        BusArrivalAlertProperties p = new BusArrivalAlertProperties();
        p.setClaimTimeoutMinutes(3);
        p.setMaxAttempts(3);
        p.setBackoffSeconds(60);
        p.setOutboxRelayBatchSize(backlog * 2);
        p.setOutboxRelayWorkers(workers);
        return new BusAlertOutboxRelayService(outboxStateService, toss, p, messengerProperties, clock);
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

    private static int intEnv(String name, int def) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? def : Integer.parseInt(v);
    }

    private static long longEnv(String name, long def) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? def : Long.parseLong(v);
    }

    /** 발송 지연을 흉내내며 각 발송 완료 시각(relay 시작 기준 ms)을 기록해 p95 를 낸다. */
    static class RecordingTossPort implements TossMessengerPort {
        private final long latencyMs;
        private final List<Long> completions = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger calls = new AtomicInteger();
        private volatile long startNanos;

        RecordingTossPort(long latencyMs) {
            this.latencyMs = latencyMs;
        }

        void begin() {
            completions.clear();
            calls.set(0);
            startNanos = System.nanoTime();
        }

        int calls() {
            return calls.get();
        }

        long percentile(double p) {
            List<Long> sorted;
            synchronized (completions) {
                sorted = new ArrayList<>(completions);
            }
            if (sorted.isEmpty()) return 0;
            Collections.sort(sorted);
            int idx = (int) Math.ceil(p * sorted.size()) - 1;
            idx = Math.max(0, Math.min(idx, sorted.size() - 1));
            return sorted.get(idx);
        }

        @Override
        public void sendMessage(long tossUserKey, String templateSetCode, Map<String, Object> context) {
            CountingBusApiPort.sleep(latencyMs);
            calls.incrementAndGet();
            completions.add((System.nanoTime() - startNanos) / 1_000_000);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        BusArrivalAlertProperties busArrivalAlertProperties() {
            BusArrivalAlertProperties p = new BusArrivalAlertProperties();
            p.setThresholdSeconds(120);
            p.setBatchSize(intEnv("BUS_ALERT_RELAY_LOAD_BACKLOG", 96) * 2);
            p.setClaimTimeoutMinutes(3);
            p.setMaxAttempts(3);
            p.setPerUserCycleCap(Integer.MAX_VALUE);
            p.setBackoffSeconds(60);
            p.setOutboxRelayBatchSize(intEnv("BUS_ALERT_RELAY_LOAD_BACKLOG", 96) * 2);
            return p;
        }

        @Bean
        TossMessengerProperties tossMessengerProperties() {
            TossMessengerProperties p = new TossMessengerProperties();
            p.setBusArrivalTemplateSetCode("BUS_ARRIVAL_RELAY_LOAD_TEST");
            return p;
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
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
        InMemoryBusRealtimeCache busRealtimeCache() {
            return new InMemoryBusRealtimeCache();
        }
    }
}
