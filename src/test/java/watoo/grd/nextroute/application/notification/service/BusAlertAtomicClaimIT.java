package watoo.grd.nextroute.application.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import watoo.grd.nextroute.common.lock.AlwaysLeaderLeaseTestConfig;
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
import watoo.grd.nextroute.application.bus.InMemoryBusRealtimeCache;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원자 enqueue 검증: 동시 worker 가 같은 PENDING 구독을 중복 적재하지 않음을 실 Postgres 로 증명한다.
 * enqueue 는 단일 SQL CTE(구독 PENDING→PROCESSING 원자 claim + outbox INSERT ON CONFLICT)라
 * 경쟁 worker 중 하나만 승자 → K개 구독 = 정확히 K개 outbox row(중복 0), 구독 전량 PROCESSING.
 * dispatch 는 Toss 를 호출하지 않는다(발송은 relay).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        BusAlertDispatchService.class,
        BusAlertStateService.class,
        BusAlertOutboxEnqueueService.class,
        BusAlertAtomicClaimIT.Config.class,
        AlwaysLeaderLeaseTestConfig.class
})
class BusAlertAtomicClaimIT {

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
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from bus_alert_notification");
        jdbcTemplate.update("delete from bus_arrival_alert");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void concurrentDispatch_atomicEnqueue_noDuplicate() throws Exception {
        int alerts = 8;
        int workers = 4;
        seedPendingAlerts(alerts);

        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = IntStream.range(0, workers)
                    .mapToObj(i -> executor.submit((Callable<Void>) () -> {
                        start.await();
                        dispatchService.dispatchDue();
                        return null;
                    }))
                    .toList();
            start.countDown();
            for (Future<?> f : futures) f.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // 원자 enqueue → 각 구독은 정확히 한 worker 가 적재 → outbox 정확히 K행(중복 0), 구독 전량 PROCESSING.
        assertThat(outboxCount()).isEqualTo(alerts);
        assertThat(statusCount("bus_arrival_alert", "PROCESSING")).isEqualTo(alerts);
        assertThat(statusCount("bus_arrival_alert", "PENDING")).isZero();
        assertThat(statusCount("bus_alert_notification", "PENDING")).isEqualTo(alerts);
    }

    private void seedPendingAlerts(int count) {
        for (int i = 1; i <= count; i++) {
            jdbcTemplate.update("""
                    insert into users (id, created_at, updated_at, toss_user_key)
                    values (?, ?, ?, ?)
                    """, (long) i, NOW, NOW, 1_000_000L + i);
            jdbcTemplate.update("""
                    insert into bus_arrival_alert
                        (id, created_at, updated_at, user_id, stop_id, route_id, ord,
                         route_name, stop_name, user_eta, bus_arrival_minutes, status,
                         expires_at, attempt_count)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (long) i, NOW, NOW, (long) i,
                    "S" + i, "R" + i, 3, "간선" + i, "정류장" + i,
                    NOW, 1, "PENDING", NOW.plusMinutes(60), 0);
        }
    }

    private long outboxCount() {
        Long c = jdbcTemplate.queryForObject("select count(*) from bus_alert_notification", Long.class);
        return c == null ? 0 : c;
    }

    private long statusCount(String table, String status) {
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
            p.setTtlMinutes(60);
            p.setBatchSize(200);
            p.setClaimTimeoutMinutes(3);
            p.setMaxAttempts(3);
            p.setPerUserCycleCap(5);
            p.setBackoffSeconds(60);
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
