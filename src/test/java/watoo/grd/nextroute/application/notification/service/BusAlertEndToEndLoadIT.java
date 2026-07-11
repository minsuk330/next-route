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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C. end-to-end 부하: 실제 운영 흐름(dispatch=enqueue → relay=send)에서 due alert 가 SENT 까지 가는 시간.
 * <p>검증: 전량 SENT(구독+outbox), PROCESSING 고착 0(중간상태 잔존 없음), 중복 0. 규모는 env 로 조정.
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
        BusAlertOutboxRelayService.class,
        BusAlertEndToEndLoadIT.Config.class
})
class BusAlertEndToEndLoadIT {

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
    @Autowired BusAlertOutboxRelayService relayService;
    @Autowired CountingTossMessengerPort tossMessengerPort;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from bus_alert_notification");
        jdbcTemplate.update("delete from bus_arrival_alert");
        jdbcTemplate.update("delete from users");
        tossMessengerPort.reset();
    }

    @Test
    void dueToSent_fullCycle_noStuck(TestReporter reporter) {
        int total = intEnv("BUS_ALERT_E2E_LOAD_TOTAL", 200);
        int groups = intEnv("BUS_ALERT_E2E_LOAD_GROUPS", 40);
        seedPending(total, groups);

        // due → enqueue (backlog 소진까지 반복), enqueue → send (outbox 소진까지 반복). batchSize 무관.
        long dispatchMs = timed(() -> drain(
                "select count(*) from bus_arrival_alert where status = 'PENDING'",
                dispatchService::dispatchDue));
        long relayMs = timed(() -> drain(
                "select count(*) from bus_alert_notification where status = 'PENDING'",
                relayService::relayOnce));
        long totalMs = dispatchMs + relayMs;

        long subSent = scalar("select count(*) from bus_arrival_alert where status = 'SENT'");
        long subStuck = scalar("select count(*) from bus_arrival_alert where status = 'PROCESSING'");
        long outboxSent = scalar("select count(*) from bus_alert_notification where status = 'SENT'");
        long outboxStuck = scalar("select count(*) from bus_alert_notification where status = 'PROCESSING'");

        // 전량 SENT, 고착(PROCESSING) 0, 중복 발송 0.
        assertThat(subSent).isEqualTo(total);
        assertThat(outboxSent).isEqualTo(total);
        assertThat(subStuck).isZero();
        assertThat(outboxStuck).isZero();
        assertThat(tossMessengerPort.calls()).isEqualTo(total);

        double perSec = totalMs == 0 ? total : total * 1000.0 / totalMs;
        Map<String, String> m = new LinkedHashMap<>();
        m.put("scenario", "e2e-load");
        m.put("total", String.valueOf(total));
        m.put("groups", String.valueOf(groups));
        m.put("dispatchMs", String.valueOf(dispatchMs));
        m.put("relayMs", String.valueOf(relayMs));
        m.put("totalMs", String.valueOf(totalMs));
        m.put("sentPerSec", String.format("%.1f", perSec));
        reporter.publishEntry(m);
        System.out.println("[BusAlertEndToEndLoadIT] " + m);
    }

    private void seedPending(int total, int groupCount) {
        for (int i = 1; i <= total; i++) {
            int g = (i - 1) % groupCount;
            jdbcTemplate.update("insert into users (id, created_at, updated_at, toss_user_key) values (?,?,?,?)",
                    (long) i, NOW, NOW, 1_000_000L + i);
            jdbcTemplate.update("""
                    insert into bus_arrival_alert
                        (id, created_at, updated_at, user_id, stop_id, route_id, ord,
                         route_name, stop_name, user_eta, bus_arrival_minutes, status, expires_at, attempt_count)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    (long) i, NOW, NOW, (long) i, "S" + g, "R" + g, 3, "간선" + g, "정류장" + g,
                    NOW, 1, "PENDING", NOW.plusMinutes(60), 0);
        }
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

    /** 지정 카운트가 0 될 때까지 step 반복(no-progress 시 중단). */
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

    @TestConfiguration
    static class Config {
        @Bean
        BusArrivalAlertProperties busArrivalAlertProperties() {
            int total = intEnv("BUS_ALERT_E2E_LOAD_TOTAL", 200);
            BusArrivalAlertProperties p = new BusArrivalAlertProperties();
            p.setThresholdSeconds(120);
            p.setBatchSize(total * 2);
            p.setClaimTimeoutMinutes(3);
            p.setMaxAttempts(3);
            p.setPerUserCycleCap(Integer.MAX_VALUE);
            p.setBackoffSeconds(60);
            p.setOutboxRelayBatchSize(total * 2);
            p.setOutboxRelayWorkers(8);
            return p;
        }

        @Bean
        TossMessengerProperties tossMessengerProperties() {
            TossMessengerProperties p = new TossMessengerProperties();
            p.setBusArrivalTemplateSetCode("BUS_ARRIVAL_E2E_LOAD_TEST");
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
        CountingTossMessengerPort tossMessengerPort() {
            return new CountingTossMessengerPort(longEnv("BUS_ALERT_E2E_LOAD_TOSS_LATENCY_MS", 0));
        }

        @Bean
        InMemoryBusRealtimeCache busRealtimeCache() {
            return new InMemoryBusRealtimeCache();
        }

        private static long longEnv(String name, long def) {
            String v = System.getenv(name);
            return (v == null || v.isBlank()) ? def : Long.parseLong(v);
        }
    }
}
