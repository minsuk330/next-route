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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.domain.notification.entity.BusArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.BusArrivalAlertRepository;
import watoo.grd.nextroute.testsupport.AbstractBusAlertOutboxPostgresIT;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * dispatch/enqueue 병목 위치 측정용 IT.
 *
 * <p>production {@link BusAlertDispatchService#dispatchDue()}의 주요 단계를 테스트에서 분리 재현한다.
 * private 메서드를 열지 않고도 findDispatchable, candidate build, enqueue write 비용을 따로 볼 수 있다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        BusAlertStateService.class,
        BusAlertOutboxEnqueueService.class,
        BusAlertDispatchBottleneckIT.Config.class
})
class BusAlertDispatchBottleneckIT extends AbstractBusAlertOutboxPostgresIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(Instant.parse("2026-06-23T03:00:00Z"), KST);

    @Autowired BusArrivalAlertRepository alertRepository;
    @Autowired BusAlertStateService stateService;
    @Autowired BusAlertOutboxEnqueueService outboxEnqueueService;
    @Autowired BusArrivalAlertProperties properties;
    @Autowired CountingBusApiPort busApiPort;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from bus_alert_notification");
        jdbcTemplate.update("delete from bus_arrival_alert");
        jdbcTemplate.update("delete from users");
        busApiPort.reset();
    }

    @Test
    void dispatchBreakdown_findBuildEnqueue(TestReporter reporter) {
        int total = intEnv("BUS_ALERT_BREAKDOWN_TOTAL", 4_000);
        int groups = intEnv("BUS_ALERT_BREAKDOWN_GROUPS", 40);
        seedPending(total, groups);

        Metrics metrics = new Metrics();
        long wallStart = System.nanoTime();
        while (pendingAlerts() > 0) {
            long before = pendingAlerts();
            runOneInstrumentedCycle(metrics);
            if (pendingAlerts() == before) break;
        }
        long wallNs = System.nanoTime() - wallStart;

        long outbox = scalar("select count(*) from bus_alert_notification");
        long distinctDedup = scalar("select count(distinct dedup_key) from bus_alert_notification");
        long parentProcessing = scalar("select count(*) from bus_arrival_alert where status = 'PROCESSING'");

        assertThat(outbox).isEqualTo(total);
        assertThat(distinctDedup).isEqualTo(outbox);
        assertThat(parentProcessing).isEqualTo(total);
        assertThat(metrics.enqueued).isEqualTo(total);

        Map<String, String> report = metrics.report(total, groups, properties.getBatchSize(), busApiPort.calls(), wallNs);
        reporter.publishEntry(report);
        System.out.println("[BusAlertDispatchBottleneckIT] " + report);
    }

    private void runOneInstrumentedCycle(Metrics metrics) {
        LocalDateTime now = LocalDateTime.now(Clock.fixed(NOW.atZone(KST).toInstant(), KST));
        LocalDateTime retryBefore = now.minusSeconds(properties.getBackoffSeconds());

        long t0 = System.nanoTime();
        stateService.expireAndReclaim(now);
        long t1 = System.nanoTime();

        List<BusArrivalAlert> due = alertRepository.findDispatchable(
                now, retryBefore, PageRequest.of(0, properties.getBatchSize()));
        long t2 = System.nanoTime();

        List<EnqueueCandidate> candidates = buildCandidates(due, now);
        long t3 = System.nanoTime();

        int enqueued = 0;
        for (EnqueueCandidate candidate : candidates) {
            if (outboxEnqueueService.enqueueTossArrival(candidate.alert(), candidate.arrivalMin())) {
                enqueued++;
            }
        }
        long t4 = System.nanoTime();

        metrics.cycles++;
        metrics.due += due.size();
        metrics.candidates += candidates.size();
        metrics.enqueued += enqueued;
        metrics.stateNs += t1 - t0;
        metrics.findNs += t2 - t1;
        metrics.buildNs += t3 - t2;
        metrics.enqueueNs += t4 - t3;
    }

    private List<EnqueueCandidate> buildCandidates(List<BusArrivalAlert> due, LocalDateTime now) {
        Map<Long, Integer> selectedPerUser = new HashMap<>();
        List<EnqueueCandidate> candidates = new ArrayList<>();
        Map<String, List<BusArrivalAlert>> groups = due.stream()
                .collect(Collectors.groupingBy(a -> a.getStopId() + "|" + a.getRouteId() + "|" + a.getOrd()));

        for (List<BusArrivalAlert> group : groups.values()) {
            BusArrivalAlert sample = group.get(0);
            List<BusArrivalInfo> arrivals = busApiPort.getArrInfoByStop(
                    sample.getStopId(), sample.getRouteId(), String.valueOf(sample.getOrd()));
            if (arrivals.isEmpty()) continue;

            BusArrivalInfo arrival = arrivals.get(0);
            for (BusArrivalAlert alert : group) {
                Integer target = selectTargetSeconds(arrival, alert.getUserEta(), now);
                if (target == null || target > properties.getThresholdSeconds()) continue;

                long userKey = alert.getUser().getTossUserKey();
                if (selectedPerUser.getOrDefault(userKey, 0) >= properties.getPerUserCycleCap()) continue;

                int arrivalMin = (int) Math.ceil(target / 60.0);
                candidates.add(new EnqueueCandidate(alert, arrivalMin));
                selectedPerUser.merge(userKey, 1, Integer::sum);
            }
        }
        return candidates;
    }

    private Integer selectTargetSeconds(BusArrivalInfo info, LocalDateTime userEta, LocalDateTime now) {
        long userGapSec = Duration.between(now, userEta).getSeconds();
        Integer best = null;
        for (Integer predict : new Integer[]{info.sectionTime1(), info.sectionTime2()}) {
            if (predict == null || predict < 0) continue;
            if (predict < userGapSec) continue;
            if (best == null || predict < best) best = predict;
        }
        return best;
    }

    private void seedPending(int total, int groupCount) {
        List<Object[]> users = new ArrayList<>(total);
        List<Object[]> alerts = new ArrayList<>(total);
        for (int i = 1; i <= total; i++) {
            int g = (i - 1) % groupCount;
            users.add(new Object[]{(long) i, NOW, NOW, 1_000_000L + i});
            alerts.add(new Object[]{
                    (long) i, NOW, NOW, (long) i, "S" + g, "R" + g, 3,
                    "간선" + g, "정류장" + g, NOW, 1, "PENDING", NOW.plusMinutes(60), 0});
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

    private long pendingAlerts() {
        return scalar("select count(*) from bus_arrival_alert where status = 'PENDING'");
    }

    private long scalar(String sql) {
        Long v = jdbcTemplate.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }

    private static int intEnv(String name, int def) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? def : Integer.parseInt(v);
    }

    private static long longEnv(String name, long def) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? def : Long.parseLong(v);
    }

    private record EnqueueCandidate(BusArrivalAlert alert, int arrivalMin) {
    }

    private static class Metrics {
        int cycles;
        long due;
        long candidates;
        long enqueued;
        long stateNs;
        long findNs;
        long buildNs;
        long enqueueNs;

        Map<String, String> report(int total, int groups, int batchSize, int busApiCalls, long wallNs) {
            long measuredNs = stateNs + findNs + buildNs + enqueueNs;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("scenario", "dispatch-bottleneck");
            m.put("total", String.valueOf(total));
            m.put("groups", String.valueOf(groups));
            m.put("batchSize", String.valueOf(batchSize));
            m.put("cycles", String.valueOf(cycles));
            m.put("busApiCalls", String.valueOf(busApiCalls));
            m.put("due", String.valueOf(due));
            m.put("candidates", String.valueOf(candidates));
            m.put("enqueued", String.valueOf(enqueued));
            m.put("wallMs", String.valueOf(ms(wallNs)));
            m.put("stateMs", String.valueOf(ms(stateNs)));
            m.put("findMs", String.valueOf(ms(findNs)));
            m.put("buildMs", String.valueOf(ms(buildNs)));
            m.put("enqueueMs", String.valueOf(ms(enqueueNs)));
            m.put("enqueueShare", percent(enqueueNs, measuredNs));
            m.put("buildShare", percent(buildNs, measuredNs));
            m.put("findShare", percent(findNs, measuredNs));
            m.put("enqueuePerSec", perSec(total, wallNs));
            return m;
        }

        private static long ms(long ns) {
            return TimeUnit.NANOSECONDS.toMillis(ns);
        }

        private static String percent(long part, long whole) {
            if (whole <= 0) return "0.0%";
            return String.format("%.1f%%", part * 100.0 / whole);
        }

        private static String perSec(long count, long ns) {
            if (ns <= 0) return String.valueOf(count);
            return String.format("%.1f", count * 1_000_000_000.0 / ns);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        BusArrivalAlertProperties busArrivalAlertProperties() {
            BusArrivalAlertProperties p = new BusArrivalAlertProperties();
            p.setThresholdSeconds(120);
            p.setBatchSize(intEnv("BUS_ALERT_BREAKDOWN_BATCH", 200));
            p.setClaimTimeoutMinutes(3);
            p.setMaxAttempts(3);
            p.setPerUserCycleCap(Integer.MAX_VALUE);
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
            return new CountingBusApiPort(longEnv("BUS_ALERT_BREAKDOWN_BUS_API_LATENCY_MS", 0));
        }
    }
}
