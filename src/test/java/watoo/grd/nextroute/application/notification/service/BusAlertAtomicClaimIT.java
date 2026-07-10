package watoo.grd.nextroute.application.notification.service;

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
 * Step A 검증: 실제(원자) claim 을 쓰면 동시 worker 가 같은 PENDING 을 중복 발송하지 않음을 증명한다.
 * {@link BusAlertConcurrentPollingIT}(race-amplified, 구설계 문서화)와 대조 — 여기선 앰프 없이 실 claim.
 * K개 PENDING 을 N worker 가 동시에 dispatchDue → Toss 호출 정확히 K회(구독당 1회), 중복 0.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        BusAlertDispatchService.class,
        BusAlertStateService.class,
        BusAlertAtomicClaimIT.Config.class
})
class BusAlertAtomicClaimIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(Instant.parse("2026-06-23T03:00:00Z"), KST);

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
                    .withInitScript("db/it/bus-alert-it-schema.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired BusAlertDispatchService dispatchService;
    @Autowired BusAlertConcurrentPollingIT.CountingTossMessengerPort tossMessengerPort;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from bus_arrival_alert");
        jdbcTemplate.update("delete from users");
        tossMessengerPort.reset();
    }

    @Test
    void concurrentDispatch_atomicClaim_noDuplicateSend() throws Exception {
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

        // 원자 claim → 각 구독은 정확히 한 worker 가 집어 정확히 1회 발송(중복 0).
        assertThat(tossMessengerPort.calls()).isEqualTo(alerts);
        assertThat(statusCount("SENT")).isEqualTo(alerts);
        assertThat(statusCount("PENDING")).isZero();
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

    private long statusCount(String status) {
        Long c = jdbcTemplate.queryForObject(
                "select count(*) from bus_arrival_alert where status = ?", Long.class, status);
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
        TossMessengerProperties tossMessengerProperties() {
            TossMessengerProperties p = new TossMessengerProperties();
            p.setBusArrivalTemplateSetCode("BUS_ARRIVAL_ATOMIC_TEST");
            return p;
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
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
