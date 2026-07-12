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
import watoo.grd.nextroute.application.bus.InMemoryBusRealtimeCache;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.config.TossMessengerProperties;
import watoo.grd.nextroute.application.notification.port.out.TossMessengerPort;
import watoo.grd.nextroute.domain.notification.entity.BusArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.BusArrivalAlertRepository;

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
 * relay wake-up 지연 개선 검증: enqueue→SENT 지연을 실 Postgres 로 측정한다.
 *
 * <p><b>wakeup</b>(측정): enqueue 직후 즉시 drain → 지연 = 발송 시간(ms).
 * <b>interval</b>(구조적 baseline): relay 가 고정 주기로만 돌면 enqueue 는 다음 tick 까지 대기 —
 * uniform[0, interval], p95 ≈ 0.95·interval. 이 대기가 wake-up 이 제거하는 hop 이다.
 * 정합성(중복 0/전량 SENT)도 함께 확인.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        BusAlertOutboxEnqueueService.class,
        BusAlertOutboxStateService.class,
        BusAlertOutboxRelayService.class,
        BusAlertRelayLatencyIT.Config.class
})
class BusAlertRelayLatencyIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(Instant.parse("2026-06-23T03:00:00Z"), KST);
    private static final int N = 50;
    private static final long INTERVAL_MS = 1000; // 비교 기준 relay 주기

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

    @Autowired BusAlertOutboxEnqueueService enqueueService;
    @Autowired BusAlertOutboxRelayService relayService;
    @Autowired BusArrivalAlertRepository alertRepository;
    @Autowired RecordingTossPort tossPort;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from bus_alert_notification");
        jdbcTemplate.update("delete from bus_arrival_alert");
        jdbcTemplate.update("delete from users");
        tossPort.reset();
    }

    @Test
    void wakeupLatency_farBelowIntervalWait(TestReporter reporter) {
        seedPending(N);
        List<BusArrivalAlert> alerts = alertRepository.findAll();
        assertThat(alerts).hasSize(N);

        // wakeup: enqueue 직후 즉시 drain(=wake 가 relay 를 바로 깨운 효과). 건별 enqueue→SENT 지연 측정.
        List<Long> latencies = new ArrayList<>(N);
        for (BusArrivalAlert alert : alerts) {
            tossPort.markEnqueue();
            enqueueService.enqueueTossArrival(alert, 1);
            relayService.drainUntilEmpty();
        }

        long sent = scalar("select count(*) from bus_alert_notification where status = 'SENT'");
        assertThat(sent).isEqualTo(N);
        assertThat(tossPort.calls()).isEqualTo(N);          // 중복 발송 0
        latencies.addAll(tossPort.latenciesMs());
        assertThat(latencies).hasSize(N);

        long wakeP50 = percentile(latencies, 0.50);
        long wakeP95 = percentile(latencies, 0.95);
        long wakeMax = percentile(latencies, 1.0);
        long intervalP95 = (long) (0.95 * INTERVAL_MS); // 구조적 대기 baseline

        // wake-up 지연이 interval 구조적 대기(p95≈950ms)보다 현저히 작다.
        assertThat(wakeP95).isLessThan(intervalP95);

        Map<String, String> m = new LinkedHashMap<>();
        m.put("scenario", "relay-latency");
        m.put("n", String.valueOf(N));
        m.put("intervalMs", String.valueOf(INTERVAL_MS));
        m.put("intervalWaitP95Ms(structural)", String.valueOf(intervalP95));
        m.put("wakeupP50Ms", String.valueOf(wakeP50));
        m.put("wakeupP95Ms", String.valueOf(wakeP95));
        m.put("wakeupMaxMs", String.valueOf(wakeMax));
        reporter.publishEntry(m);
        System.out.println("[BusAlertRelayLatencyIT] " + m);
    }

    private void seedPending(int n) {
        List<Object[]> users = new ArrayList<>(n);
        List<Object[]> alerts = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
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
    }

    private long scalar(String sql) {
        Long v = jdbcTemplate.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }

    private static long percentile(List<Long> values, double p) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    /** 발송 시각을 직전 enqueue 기준으로 기록해 건별 enqueue→SENT 지연을 낸다. workers=1 FIFO 전제. */
    static class RecordingTossPort implements TossMessengerPort {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        private volatile long enqueueNanos;

        void markEnqueue() {
            enqueueNanos = System.nanoTime();
        }

        void reset() {
            calls.set(0);
            latencies.clear();
        }

        int calls() {
            return calls.get();
        }

        List<Long> latenciesMs() {
            synchronized (latencies) {
                return new ArrayList<>(latencies);
            }
        }

        @Override
        public void sendMessage(long tossUserKey, String templateSetCode, Map<String, Object> context) {
            latencies.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - enqueueNanos));
            calls.incrementAndGet();
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        BusArrivalAlertProperties busArrivalAlertProperties() {
            BusArrivalAlertProperties p = new BusArrivalAlertProperties();
            p.setClaimTimeoutMinutes(3);
            p.setMaxAttempts(3);
            p.setBackoffSeconds(60);
            p.setOutboxRelayBatchSize(200);
            p.setOutboxRelayWorkers(1); // FIFO 지연 측정
            return p;
        }

        @Bean
        TossMessengerProperties tossMessengerProperties() {
            TossMessengerProperties p = new TossMessengerProperties();
            p.setBusArrivalTemplateSetCode("BUS_ARRIVAL_LATENCY_TEST");
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
        RecordingTossPort tossMessengerPort() {
            return new RecordingTossPort();
        }

        @Bean
        InMemoryBusRealtimeCache busRealtimeCache() {
            return new InMemoryBusRealtimeCache();
        }
    }
}
