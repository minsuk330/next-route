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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step C 검증: relay 발송 병렬화가 처리량을 올리되 정합성을 유지함을 실 Postgres 로 증명한다.
 *
 * <p>동일 backlog(N outbox PENDING)를 Toss 지연을 고정한 채 workers=1(순차) vs workers=K(병렬)로
 * 각각 relayOnce 하고 벽시계 시간을 비교한다. claim 은 이미 원자(SKIP LOCKED)라 병렬 워커가 같은 행을
 * 중복 발송하지 않음 — 두 경우 모두 Toss 호출 = N(중복 0), 전량 SENT.
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
        BusAlertOutboxRelayThroughputIT.Config.class
})
class BusAlertOutboxRelayThroughputIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(Instant.parse("2026-06-23T03:00:00Z"), KST);

    private static final int BACKLOG = 24;
    private static final long TOSS_LATENCY_MS = 30;
    private static final int PARALLEL_WORKERS = 8;

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

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from bus_alert_notification");
        jdbcTemplate.update("delete from bus_arrival_alert");
        jdbcTemplate.update("delete from users");
        properties.setOutboxEnabled(true);
        properties.setOutboxRelayBatchSize(BACKLOG * 2);
    }

    @Test
    void parallelRelayBeatsSequential_withoutDuplicateSend(TestReporter reporter) {
        // ── 순차(workers=1) ──
        seedBacklog();
        BusAlertDispatcherLoadIT.CountingTossMessengerPort seqToss =
                new BusAlertDispatcherLoadIT.CountingTossMessengerPort(TOSS_LATENCY_MS);
        BusAlertOutboxRelayService seqRelay = relay(seqToss, 1);
        long seqMs = timed(seqRelay::relayOnce);

        assertThat(seqToss.calls()).isEqualTo(BACKLOG);
        assertThat(count("bus_alert_notification", "SENT")).isEqualTo(BACKLOG);
        assertThat(count("bus_arrival_alert", "SENT")).isEqualTo(BACKLOG);
        seqRelay.shutdown();

        // ── 병렬(workers=K), 동일 backlog 재구성 ──
        jdbcTemplate.update("delete from bus_alert_notification");
        jdbcTemplate.update("delete from bus_arrival_alert");
        jdbcTemplate.update("delete from users");
        seedBacklog();
        BusAlertDispatcherLoadIT.CountingTossMessengerPort parToss =
                new BusAlertDispatcherLoadIT.CountingTossMessengerPort(TOSS_LATENCY_MS);
        BusAlertOutboxRelayService parRelay = relay(parToss, PARALLEL_WORKERS);
        long parMs = timed(parRelay::relayOnce);

        // 병렬이라도 claim 이 원자 → Toss 정확히 N회(중복 0), 전량 SENT.
        assertThat(parToss.calls()).isEqualTo(BACKLOG);
        assertThat(count("bus_alert_notification", "SENT")).isEqualTo(BACKLOG);
        assertThat(count("bus_alert_notification", "PENDING")).isZero();
        assertThat(count("bus_arrival_alert", "SENT")).isEqualTo(BACKLOG);
        parRelay.shutdown();

        // 병렬이 순차 대비 명확히 빠름(지연 지배 구간). 여유 마진으로 flakiness 회피.
        assertThat(parMs).isLessThan(seqMs / 2);

        publish(reporter, seqMs, parMs, seqToss.calls(), parToss.calls());
    }

    /** N개 PENDING 구독 seed 후 dispatchDue(outbox on) → N개 outbox PENDING 인계. */
    private void seedBacklog() {
        for (int i = 1; i <= BACKLOG; i++) {
            jdbcTemplate.update("insert into users (id, created_at, updated_at, toss_user_key) values (?,?,?,?)",
                    (long) i, NOW, NOW, 1_000_000L + i);
            jdbcTemplate.update("""
                    insert into bus_arrival_alert
                        (id, created_at, updated_at, user_id, stop_id, route_id, ord,
                         route_name, stop_name, user_eta, bus_arrival_minutes, status, expires_at, attempt_count)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    (long) i, NOW, NOW, (long) i, "S" + i, "R" + i, 3, "간선" + i, "정류장" + i,
                    NOW, 1, "PENDING", NOW.plusMinutes(60), 0);
        }
        dispatchService.dispatchDue();
        assertThat(count("bus_alert_notification", "PENDING")).isEqualTo(BACKLOG);
    }

    private BusAlertOutboxRelayService relay(
            BusAlertDispatcherLoadIT.CountingTossMessengerPort toss, int workers) {
        BusArrivalAlertProperties p = new BusArrivalAlertProperties();
        p.setClaimTimeoutMinutes(3);
        p.setMaxAttempts(3);
        p.setBackoffSeconds(60);
        p.setOutboxEnabled(true);
        p.setOutboxRelayBatchSize(BACKLOG * 2);
        p.setOutboxRelayWorkers(workers);
        return new BusAlertOutboxRelayService(outboxStateService, toss, p, messengerProperties, clock);
    }

    private long timed(Runnable r) {
        long start = System.nanoTime();
        r.run();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private long count(String table, String status) {
        Long c = jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where status = ?", Long.class, status);
        return c == null ? 0 : c;
    }

    private void publish(TestReporter reporter, long seqMs, long parMs, int seqCalls, int parCalls) {
        double speedup = parMs == 0 ? seqMs : seqMs / (double) parMs;
        Map<String, String> m = new LinkedHashMap<>();
        m.put("scenario", "relay-parallelization");
        m.put("backlog", String.valueOf(BACKLOG));
        m.put("tossLatencyMs", String.valueOf(TOSS_LATENCY_MS));
        m.put("workers", "1 -> " + PARALLEL_WORKERS);
        m.put("seqMs", String.valueOf(seqMs));
        m.put("parMs", String.valueOf(parMs));
        m.put("speedup", String.format("%.2fx", speedup));
        m.put("seqTossCalls", String.valueOf(seqCalls));
        m.put("parTossCalls", String.valueOf(parCalls));
        reporter.publishEntry(m);
        System.out.println("[BusAlertOutboxRelayThroughputIT] " + m);
    }

    @TestConfiguration
    static class Config {
        @Bean
        BusArrivalAlertProperties busArrivalAlertProperties() {
            BusArrivalAlertProperties p = new BusArrivalAlertProperties();
            p.setThresholdSeconds(120);
            p.setBatchSize(BACKLOG * 2);
            p.setClaimTimeoutMinutes(3);
            p.setMaxAttempts(3);
            p.setPerUserCycleCap(5);
            p.setBackoffSeconds(60);
            p.setOutboxEnabled(true);
            p.setOutboxRelayBatchSize(BACKLOG * 2);
            return p;
        }

        @Bean
        TossMessengerProperties tossMessengerProperties() {
            TossMessengerProperties p = new TossMessengerProperties();
            p.setBusArrivalTemplateSetCode("BUS_ARRIVAL_RELAY_THROUGHPUT_TEST");
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
        BusAlertConcurrentPollingIT.CountingBusApiPort busApiPort() {
            return new BusAlertConcurrentPollingIT.CountingBusApiPort(0);
        }

        @Bean
        BusAlertConcurrentPollingIT.CountingTossMessengerPort tossMessengerPort() {
            return new BusAlertConcurrentPollingIT.CountingTossMessengerPort(0);
        }
    }
}
