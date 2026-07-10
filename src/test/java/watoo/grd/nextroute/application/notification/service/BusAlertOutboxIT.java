package watoo.grd.nextroute.application.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step B 검증(outbox on): polling 은 enqueue 만 하고 Toss 를 호출하지 않으며(send-after-commit),
 * 발송은 relay 가 수행함을 실 Postgres 로 확인한다. dedup·crash 재발송도 검증.
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
        BusAlertOutboxIT.Config.class
})
class BusAlertOutboxIT {

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
    @Autowired BusArrivalAlertProperties properties;
    @Autowired BusAlertConcurrentPollingIT.CountingTossMessengerPort tossMessengerPort;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from bus_alert_notification");
        jdbcTemplate.update("delete from bus_arrival_alert");
        jdbcTemplate.update("delete from users");
        tossMessengerPort.reset();
        properties.setOutboxEnabled(true);
    }

    @Test
    void pollingEnqueuesWithoutCallingToss() {
        seedPending(1);

        dispatchService.dispatchDue();

        // 핵심: polling 은 Toss 를 절대 호출하지 않는다.
        assertThat(tossMessengerPort.calls()).isZero();
        // enqueue 로 outbox PENDING 1건, 구독은 PROCESSING(인계됨).
        assertThat(count("bus_alert_notification", "PENDING")).isEqualTo(1);
        assertThat(count("bus_arrival_alert", "PROCESSING")).isEqualTo(1);
    }

    @Test
    void relaySendsOnceAndCompletesSubscription() {
        seedPending(1);
        dispatchService.dispatchDue();     // enqueue only

        relayService.relayOnce();          // 여기서 발송

        assertThat(tossMessengerPort.calls()).isEqualTo(1);
        assertThat(count("bus_alert_notification", "SENT")).isEqualTo(1);
        assertThat(count("bus_arrival_alert", "SENT")).isEqualTo(1);
    }

    @Test
    void duplicateEnqueueIgnored_oneOutboxRow() {
        seedPending(1);
        dispatchService.dispatchDue();
        dispatchService.dispatchDue();     // 두 번째: 구독이 이미 PROCESSING → enqueue no-op

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from bus_alert_notification", Long.class)).isEqualTo(1);
        assertThat(count("bus_arrival_alert", "PROCESSING")).isEqualTo(1);
    }

    @Test
    void crashBeforeMark_reclaimRedelivers() {
        seedPending(1);
        dispatchService.dispatchDue();
        Long id = jdbcTemplate.queryForObject(
                "select id from bus_alert_notification where status='PENDING'", Long.class);

        // relay claim 후 mark 없이 crash 모사.
        jdbcTemplate.update("update bus_alert_notification set status='PROCESSING', processing_started_at=?, attempt_count=1 where id=?",
                NOW.minusMinutes(properties.getClaimTimeoutMinutes() + 1), id);
        relayService.relayOnce(); // reclaim(고착→PENDING) → 재claim → 발송

        assertThat(tossMessengerPort.calls()).isEqualTo(1);
        assertThat(count("bus_alert_notification", "SENT")).isEqualTo(1);
    }

    private void seedPending(int i) {
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

    private long count(String table, String status) {
        Long c = jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where status = ?", Long.class, status);
        return c == null ? 0 : c;
    }

    @TestConfiguration
    static class Config {
        @Bean
        BusArrivalAlertProperties busArrivalAlertProperties() {
            BusArrivalAlertProperties p = new BusArrivalAlertProperties();
            p.setThresholdSeconds(120);
            p.setBatchSize(200);
            p.setClaimTimeoutMinutes(3);
            p.setMaxAttempts(3);
            p.setPerUserCycleCap(5);
            p.setBackoffSeconds(60);
            p.setOutboxEnabled(true);
            p.setOutboxRelayBatchSize(200);
            return p;
        }

        @Bean
        TossMessengerProperties tossMessengerProperties() {
            TossMessengerProperties p = new TossMessengerProperties();
            p.setBusArrivalTemplateSetCode("BUS_ARRIVAL_OUTBOX_TEST");
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
