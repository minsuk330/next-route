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
import watoo.grd.nextroute.application.bus.InMemoryBusRealtimeCache;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.config.TossMessengerProperties;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.notifination.BusAlertRelayLoop;
import watoo.grd.nextroute.domain.notification.entity.BusArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.BusArrivalAlertRepository;
import watoo.grd.nextroute.testsupport.AbstractBusAlertOutboxPostgresIT;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * wake-up 배선 검증(실 경로): enqueue afterCommit 이벤트 → RelayWakeListener → RelayWaker →
 * BusAlertRelayLoop 이 즉시 drain → SENT. sweep 을 크게 둬 오직 wake 로만 발송됨을 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        BusAlertOutboxEnqueueService.class,
        BusAlertOutboxStateService.class,
        BusAlertOutboxRelayService.class,
        RelayWaker.class,
        RelayWakeListener.class,
        BusAlertRelayLoop.class,
        BusAlertRelayWakeupIT.Config.class
})
class BusAlertRelayWakeupIT extends AbstractBusAlertOutboxPostgresIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(Instant.parse("2026-06-23T03:00:00Z"), KST);

    @DynamicPropertySource
    static void wakeupProperties(DynamicPropertyRegistry r) {
        r.add("notification.bus-arrival.enabled", () -> "true"); // dev 프로필 기본 false 오버라이드
        r.add("notification.bus-arrival.outbox-sweep-interval-ms", () -> "60000"); // sweep 사실상 off → wake 로만 발송
    }

    @Autowired BusAlertOutboxEnqueueService enqueueService;
    @Autowired BusArrivalAlertRepository alertRepository;
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
    void enqueue_wakesRelayLoop_sendsWithoutWaitingSweep() throws Exception {
        seedOne();
        BusArrivalAlert alert = alertRepository.findAll().get(0);

        long start = System.nanoTime();
        enqueueService.enqueueTossArrival(alert, 1); // afterCommit → wake → loop drain

        // sweep=60s 인데도 wake 로 즉시 발송돼야 함(2s 내).
        boolean sent = awaitUntil(() ->
                scalar("select count(*) from bus_alert_notification where status = 'SENT'") == 1, 2000);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(sent).as("wake-up 으로 sweep 대기 없이 발송").isTrue();
        assertThat(elapsedMs).isLessThan(60000L); // sweep 주기와 무관하게 빠름
        assertThat(tossMessengerPort.calls()).isEqualTo(1);
        assertThat(scalar("select count(*) from bus_arrival_alert where status = 'SENT'")).isEqualTo(1);
    }

    private void seedOne() {
        jdbcTemplate.update("insert into users (id, created_at, updated_at, toss_user_key) values (?,?,?,?)",
                1L, NOW, NOW, 1_000_001L);
        jdbcTemplate.update("""
                insert into bus_arrival_alert
                    (id, created_at, updated_at, user_id, stop_id, route_id, ord,
                     route_name, stop_name, user_eta, bus_arrival_minutes, status, expires_at, attempt_count)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, 1L, NOW, NOW, 1L, "S1", "R1", 3, "간선1", "정류장1", NOW, 1, "PENDING", NOW.plusMinutes(60), 0);
    }

    private boolean awaitUntil(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return cond.getAsBoolean();
    }

    private long scalar(String sql) {
        Long v = jdbcTemplate.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }

    @TestConfiguration
    static class Config {
        @Bean
        BusArrivalAlertProperties busArrivalAlertProperties() {
            BusArrivalAlertProperties p = new BusArrivalAlertProperties();
            p.setEnabled(true);
            p.setClaimTimeoutMinutes(3);
            p.setMaxAttempts(3);
            p.setBackoffSeconds(60);
            p.setOutboxRelayBatchSize(200);
            p.setOutboxRelayWorkers(1);
            p.setOutboxSweepIntervalMs(60000);
            return p;
        }

        @Bean
        TossMessengerProperties tossMessengerProperties() {
            TossMessengerProperties p = new TossMessengerProperties();
            p.setBusArrivalTemplateSetCode("BUS_ARRIVAL_WAKEUP_TEST");
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
        CountingTossMessengerPort tossMessengerPort() {
            return new CountingTossMessengerPort(0);
        }

        @Bean
        InMemoryBusRealtimeCache busRealtimeCache() {
            return new InMemoryBusRealtimeCache();
        }
    }
}
