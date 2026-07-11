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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A. enqueue 부하: polling/dispatch 가 초당 몇 건까지 outbox 에 적재하는지 실 Postgres 로 측정한다.
 * <p>검증: 중복 outbox row 0(distinct dedup_key == 총행수), parent PROCESSING 수 = enqueue 수.
 * dispatch 는 Toss 를 호출하지 않는다(enqueue-only). 규모는 env(BUS_ALERT_ENQUEUE_LOAD_TOTAL 등)로 조정.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        BusAlertDispatchService.class,
        BusAlertStateService.class,
        BusAlertOutboxEnqueueService.class,
        BusAlertEnqueueLoadIT.Config.class
})
class BusAlertEnqueueLoadIT {

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
        // 실측용 batch-size 오버라이드(auto-bound 프로퍼티에 반영됨).
        String batch = System.getenv("BUS_ALERT_ENQUEUE_LOAD_BATCH");
        if (batch != null && !batch.isBlank()) {
            r.add("notification.bus-arrival.batch-size", () -> batch);
        }
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
    void enqueueThroughput_noDuplicate_parentMatches(TestReporter reporter) {
        int total = intEnv("BUS_ALERT_ENQUEUE_LOAD_TOTAL", 400);
        int groups = intEnv("BUS_ALERT_ENQUEUE_LOAD_GROUPS", 40);
        seedPending(total, groups);

        // backlog 를 사이클당 batchSize 만큼 소진(실제 1s 폴링 흐름). 전량 enqueue 까지 총 시간 측정.
        long start = System.nanoTime();
        int cycles = 0;
        while (scalar("select count(*) from bus_arrival_alert where status = 'PENDING'") > 0) {
            long before = scalar("select count(*) from bus_arrival_alert where status = 'PENDING'");
            dispatchService.dispatchDue();
            cycles++;
            if (scalar("select count(*) from bus_arrival_alert where status = 'PENDING'") == before) break; // no-progress 가드
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        long outbox = scalar("select count(*) from bus_alert_notification");
        long distinctDedup = scalar("select count(distinct dedup_key) from bus_alert_notification");
        long parentProcessing = scalar("select count(*) from bus_arrival_alert where status = 'PROCESSING'");
        long outboxPending = scalar("select count(*) from bus_alert_notification where status = 'PENDING'");

        // 전량 enqueue, 중복 0, parent 인계 일치, 아직 미발송(PENDING).
        assertThat(outbox).isEqualTo(total);
        assertThat(distinctDedup).isEqualTo(outbox);        // dedup_key 중복 0
        assertThat(parentProcessing).isEqualTo(outbox);      // parent PROCESSING 수 일치
        assertThat(outboxPending).isEqualTo(total);          // enqueue-only(발송 전)

        double perSec = elapsedMs == 0 ? total : total * 1000.0 / elapsedMs;
        Map<String, String> m = new LinkedHashMap<>();
        m.put("scenario", "enqueue-load");
        m.put("total", String.valueOf(total));
        m.put("groups", String.valueOf(groups));
        m.put("cycles", String.valueOf(cycles));
        m.put("elapsedMs", String.valueOf(elapsedMs));
        m.put("enqueuePerSec", String.format("%.1f", perSec));
        reporter.publishEntry(m);
        System.out.println("[BusAlertEnqueueLoadIT] " + m);
    }

    private void seedPending(int total, int groupCount) {
        List<Object[]> users = IntStream.rangeClosed(1, total)
                .mapToObj(i -> new Object[]{(long) i, NOW, NOW, 1_000_000L + i})
                .toList();
        jdbcTemplate.batchUpdate(
                "insert into users (id, created_at, updated_at, toss_user_key) values (?,?,?,?)", users);

        List<Object[]> alerts = new ArrayList<>(total);
        for (int i = 1; i <= total; i++) {
            int g = (i - 1) % groupCount;
            alerts.add(new Object[]{
                    (long) i, NOW, NOW, (long) i, "S" + g, "R" + g, 3,
                    "간선" + g, "정류장" + g, NOW, 1, "PENDING", NOW.plusMinutes(60), 0});
        }
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

    private long timed(Runnable r) {
        long start = System.nanoTime();
        r.run();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private static int intEnv(String name, int def) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? def : Integer.parseInt(v);
    }

    @TestConfiguration
    static class Config {
        @Bean
        BusArrivalAlertProperties busArrivalAlertProperties() {
            BusArrivalAlertProperties p = new BusArrivalAlertProperties();
            p.setThresholdSeconds(120);
            p.setBatchSize(intEnv("BUS_ALERT_ENQUEUE_LOAD_TOTAL", 400) * 2);
            p.setClaimTimeoutMinutes(3);
            p.setMaxAttempts(3);
            p.setPerUserCycleCap(Integer.MAX_VALUE); // 유저당 1건이라 cap 은 부하 측정에 방해 안 되게 해제
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
