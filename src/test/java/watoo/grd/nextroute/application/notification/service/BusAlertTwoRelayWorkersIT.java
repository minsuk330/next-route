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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.config.TossMessengerProperties;
import watoo.grd.nextroute.application.notification.port.out.TossMessengerPort;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 구조의 Worker 인스턴스 2개가 같은 PostgreSQL outbox를 동시에 drain하는 경쟁 검증.
 *
 * <p>각 Relay 인스턴스는 내부 발송 worker를 1개만 사용한다. 두 호출 스레드를 동시에 출발시켜
 * {@code SKIP LOCKED} claim이 서로 다른 row를 가져가는지, 중복 발송·PROCESSING 고착 없이
 * 전량 SENT로 끝나는지 확인한다. 외부 Toss는 호출하지 않고 수신자별 호출을 추적하는 지연 stub을 쓴다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        BusAlertOutboxStateService.class,
        BusAlertTwoRelayWorkersIT.Config.class
})
class BusAlertTwoRelayWorkersIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

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

    @Autowired BusAlertOutboxStateService stateService;
    @Autowired TossMessengerProperties messengerProperties;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from bus_alert_notification");
        jdbcTemplate.update("delete from bus_arrival_alert");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void twoRelayInstances_noDuplicate_noStuck_allSent(TestReporter reporter) throws Exception {
        int total = intEnv("BUS_ALERT_TWO_RELAY_TOTAL", 200);
        int claimBatch = intEnv("BUS_ALERT_TWO_RELAY_BATCH", 10);
        long tossLatencyMs = longEnv("BUS_ALERT_TWO_RELAY_TOSS_LATENCY_MS", 20);
        seedPendingOutbox(total);

        TrackingTossMessengerPort toss = new TrackingTossMessengerPort(tossLatencyMs);
        BusAlertOutboxRelayService first = relay(toss, claimBatch);
        BusAlertOutboxRelayService second = relay(toss, claimBatch);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        long started = System.nanoTime();
        try {
            Future<?> one = callers.submit(() -> drainAs("relay-instance-1", first, start));
            Future<?> two = callers.submit(() -> drainAs("relay-instance-2", second, start));
            start.countDown();
            one.get(60, TimeUnit.SECONDS);
            two.get(60, TimeUnit.SECONDS);
        } finally {
            callers.shutdownNow();
            first.shutdown();
            second.shutdown();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        long sent = count("bus_alert_notification", "SENT");
        long processing = count("bus_alert_notification", "PROCESSING");
        long pending = count("bus_alert_notification", "PENDING");
        long parentSent = count("bus_arrival_alert", "SENT");

        assertThat(sent).isEqualTo(total);
        assertThat(parentSent).isEqualTo(total);
        assertThat(processing).isZero();
        assertThat(pending).isZero();
        assertThat(toss.totalCalls()).isEqualTo(total);
        assertThat(toss.distinctRecipients()).isEqualTo(total);
        assertThat(toss.duplicateRecipients()).isZero();
        assertThat(toss.callsByThread().getOrDefault("relay-instance-1", 0)).isPositive();
        assertThat(toss.callsByThread().getOrDefault("relay-instance-2", 0)).isPositive();

        Map<String, String> result = new LinkedHashMap<>();
        result.put("scenario", "two-relay-workers");
        result.put("total", String.valueOf(total));
        result.put("claimBatch", String.valueOf(claimBatch));
        result.put("tossLatencyMs", String.valueOf(tossLatencyMs));
        result.put("elapsedMs", String.valueOf(elapsedMs));
        result.put("worker1Calls", String.valueOf(toss.callsByThread().getOrDefault("relay-instance-1", 0)));
        result.put("worker2Calls", String.valueOf(toss.callsByThread().getOrDefault("relay-instance-2", 0)));
        result.put("duplicateSends", String.valueOf(toss.duplicateRecipients()));
        result.put("stuckProcessing", String.valueOf(processing));
        result.put("sent", String.valueOf(sent));
        reporter.publishEntry(result);
        System.out.println("[BusAlertTwoRelayWorkersIT] " + result);
    }

    private void seedPendingOutbox(int total) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Object[]> users = IntStream.rangeClosed(1, total)
                .mapToObj(i -> new Object[]{(long) i, now, now, 1_000_000L + i})
                .toList();
        jdbcTemplate.batchUpdate(
                "insert into users (id, created_at, updated_at, toss_user_key) values (?,?,?,?)", users);

        List<Object[]> alerts = new ArrayList<>(total);
        List<Object[]> notifications = new ArrayList<>(total);
        for (int i = 1; i <= total; i++) {
            alerts.add(new Object[]{
                    (long) i, now, now, (long) i, "S" + i, "R" + i, 3,
                    "간선" + i, "정류장" + i, now, 1, "PROCESSING",
                    now.plusMinutes(60), now, 1});
            notifications.add(new Object[]{
                    (long) i, now, now, (long) i, "BUS_ARRIVAL:" + i + ":ARRIVAL:TOSS",
                    "TOSS", "ARRIVAL", "PENDING", now.minusSeconds(1),
                    "{\"routeName\":\"간선\",\"stopName\":\"정류장\",\"arrivalMin\":1}", 0, 0});
        }
        jdbcTemplate.batchUpdate("""
                insert into bus_arrival_alert
                    (id, created_at, updated_at, user_id, stop_id, route_id, ord,
                     route_name, stop_name, user_eta, bus_arrival_minutes, status,
                     expires_at, processing_started_at, attempt_count)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, alerts);
        jdbcTemplate.batchUpdate("""
                insert into bus_alert_notification
                    (id, created_at, updated_at, alert_id, dedup_key, channel, event_type,
                     status, scheduled_at, payload, attempt_count, requeue_count)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, notifications);
    }

    private BusAlertOutboxRelayService relay(TossMessengerPort toss, int claimBatch) {
        BusArrivalAlertProperties p = new BusArrivalAlertProperties();
        p.setClaimTimeoutMinutes(3);
        p.setMaxAttempts(3);
        p.setBackoffSeconds(60);
        p.setOutboxRelayBatchSize(claimBatch);
        p.setOutboxRelayWorkers(1);
        p.setOutboxRelayMaxBatches(1_000);
        p.setOutboxDrainMaxMillis(30_000);
        return new BusAlertOutboxRelayService(stateService, toss, p, messengerProperties, clock);
    }

    private void drainAs(String name, BusAlertOutboxRelayService relay, CountDownLatch start) {
        try {
            Thread.currentThread().setName(name);
            start.await();
            relay.drainUntilEmpty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private long count(String table, String status) {
        Long value = jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where status = ?", Long.class, status);
        return value == null ? 0 : value;
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
            BusArrivalAlertProperties p = new BusArrivalAlertProperties();
            p.setClaimTimeoutMinutes(3);
            p.setMaxAttempts(3);
            p.setBackoffSeconds(60);
            return p;
        }

        @Bean
        TossMessengerProperties tossMessengerProperties() {
            TossMessengerProperties p = new TossMessengerProperties();
            p.setBusArrivalTemplateSetCode("BUS_ARRIVAL_TWO_RELAY_WORKERS_TEST");
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
    }

    private static final class TrackingTossMessengerPort implements TossMessengerPort {
        private final long latencyMs;
        private final ConcurrentHashMap<Long, AtomicInteger> callsByRecipient = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicInteger> callsByThread = new ConcurrentHashMap<>();

        private TrackingTossMessengerPort(long latencyMs) {
            this.latencyMs = latencyMs;
        }

        @Override
        public void sendMessage(long tossUserKey, String templateSetCode, Map<String, Object> context) {
            callsByRecipient.computeIfAbsent(tossUserKey, ignored -> new AtomicInteger()).incrementAndGet();
            callsByThread.computeIfAbsent(Thread.currentThread().getName(), ignored -> new AtomicInteger())
                    .incrementAndGet();
            CountingBusApiPort.sleep(latencyMs);
        }

        private int totalCalls() {
            return callsByRecipient.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        private int distinctRecipients() {
            return callsByRecipient.size();
        }

        private long duplicateRecipients() {
            return callsByRecipient.values().stream().filter(value -> value.get() > 1).count();
        }

        private Map<String, Integer> callsByThread() {
            Map<String, Integer> result = new LinkedHashMap<>();
            callsByThread.forEach((key, value) -> result.put(key, value.get()));
            return result;
        }
    }
}
