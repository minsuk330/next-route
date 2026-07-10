package watoo.grd.nextroute.domain.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.application.notification.config.RouteArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.service.RouteAlertDispatchStateService;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertPhase;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertReliability;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlertNotification;
import watoo.grd.nextroute.domain.notification.entity.RouteNotificationStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteNotificationType;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertNotificationRepository;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertRepository;
import watoo.grd.nextroute.domain.user.entity.User;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실 Postgres 기반 장애주입 IT: outbox SKIP LOCKED claim 의 정합성(동시 claim 단일 승자, crash 후 재발송,
 * dedup unique, stale 정리)을 검증한다. 락 의미론상 실 DB 필수. 스키마는 db/it/route-alert-it-schema.sql.
 * <p>테스트 트랜잭션을 끄고(NOT_SUPPORTED) 각 시나리오가 실제 커밋/별도 커넥션으로 동작하게 한다
 * (SKIP LOCKED 는 커밋된 행에만 의미가 있으므로). 정리는 @BeforeEach 의 truncate.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({RouteAlertDispatchStateService.class, RouteArrivalAlertProperties.class,
        RouteAlertNotificationOutboxIT.Beans.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RouteAlertNotificationOutboxIT {

    /** @DataJpaTest 슬라이스에 없는 협력 빈(ObjectMapper/Clock) 공급. */
    @TestConfiguration
    static class Beans {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean Clock clock() { return Clock.systemDefaultZone(); }
    }

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
                    .withInitScript("db/it/route-alert-it-schema.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired RouteAlertDispatchStateService stateService;
    @Autowired RouteArrivalAlertNotificationRepository notificationRepository;
    @Autowired RouteArrivalAlertRepository alertRepository;
    @Autowired RouteArrivalAlertProperties properties;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;

    TransactionTemplate tx;
    final Clock clock = Clock.systemDefaultZone();

    @BeforeEach
    void clean() {
        tx = new TransactionTemplate(txManager);
        jdbc.execute("TRUNCATE route_arrival_alert_notification, route_arrival_alert, users RESTART IDENTITY CASCADE");
    }

    LocalDateTime now() { return LocalDateTime.now(clock); }

    /** ACTIVE + WAITING_DEPARTURE alert 를 커밋하고 PENDING outbox row 를 만들어 id 반환. */
    private long seedPending(long tossKey, String dedup, LocalDateTime scheduledAt) {
        return tx.execute(s -> {
            User user = em.merge(new User(tossKey));
            em.flush();
            LocalDateTime target = now().plusMinutes(120);
            RouteArrivalAlert a = RouteArrivalAlert.builder()
                    .user(user).startX(1.0).startY(2.0).startName("집").endX(3.0).endY(4.0).endName("회사")
                    .targetArrivalAt(target).userLeadMinutes(10).bufferMinutes(5)
                    .baselineDurationSec(1800).baselineLeaveByAt(target.minusMinutes(35))
                    .firstCheckAt(now()).reliability(RouteAlertReliability.MEDIUM)
                    .expiresAt(target.plusMinutes(30)).build();
            a.applyFirstCheck(1800, target.minusMinutes(35), target.minusMinutes(45),
                    RouteAlertReliability.MEDIUM, null); // → WAITING_DEPARTURE
            em.persist(a);
            RouteArrivalAlertNotification n = RouteArrivalAlertNotification.builder()
                    .alert(a).type(RouteNotificationType.DEPARTURE).dedupKey(dedup)
                    .scheduledAt(scheduledAt).targetLeaveByAt(target.minusMinutes(45)).payload(null).build();
            em.persist(n);
            em.flush();
            return n.getId();
        });
    }

    private RouteNotificationStatus statusOf(long id) {
        return tx.execute(s -> notificationRepository.findById(id).orElseThrow().getStatus());
    }

    private int attemptOf(long id) {
        return tx.execute(s -> notificationRepository.findById(id).orElseThrow().getAttemptCount());
    }

    // ── 동시 claim: 단일 승자, disjoint ─────────────────────────────────────────────

    @Test
    void concurrentClaim_disjointSingleWinners() throws InterruptedException {
        int rows = 12;
        for (int i = 0; i < rows; i++) seedPending(2000 + i, "dep:" + i, now().minusSeconds(1));

        List<Long> a = new CopyOnWriteArrayList<>();
        List<Long> b = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        Runnable claimAll = () -> {
            try {
                start.await();
                List<Long> mine = (Thread.currentThread().getName().equals("A") ? a : b);
                List<Long> got;
                do {
                    got = stateService.claimBatch(now(), 4);
                    mine.addAll(got);
                } while (!got.isEmpty());
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        Thread ta = new Thread(claimAll, "A");
        Thread tb = new Thread(claimAll, "B");
        ta.start(); tb.start();
        start.countDown();
        ta.join(); tb.join();

        Set<Long> setA = Set.copyOf(a);
        Set<Long> setB = Set.copyOf(b);
        assertThat(setA).doesNotContainAnyElementsOf(setB);           // disjoint
        assertThat(setA.size() + setB.size()).isEqualTo(rows);        // 유실 0
        // 전부 정확히 1회 claim → attempt_count=1
        assertThat(jdbc.queryForObject(
                "select count(*) from route_arrival_alert_notification where status='PROCESSING' and attempt_count=1",
                Integer.class)).isEqualTo(rows);
    }

    // ── claim 후 crash → reclaim → 재발송 1회(유실 0) ─────────────────────────────────

    @Test
    void crashAfterClaim_reclaimThenRedeliverOnce() {
        long id = seedPending(3000, "dep:crash", now().minusSeconds(1));
        List<Long> claimed = stateService.claimBatch(now(), 10);
        assertThat(claimed).containsExactly(id);
        assertThat(attemptOf(id)).isEqualTo(1);

        // crash 모사: mark 없이 processing_started_at 을 claimTimeout 이전으로.
        jdbc.update("update route_arrival_alert_notification set processing_started_at = ? where id = ?",
                now().minusMinutes(properties.getClaimTimeoutMinutes() + 1), id);

        stateService.reclaim(now());
        assertThat(statusOf(id)).isEqualTo(RouteNotificationStatus.PENDING);

        // 재claim → attempt 2, 발송성공 mark → SENT 정확히 1행.
        assertThat(stateService.claimBatch(now(), 10)).containsExactly(id);
        assertThat(attemptOf(id)).isEqualTo(2);
        stateService.markDepartureSent(id);
        assertThat(statusOf(id)).isEqualTo(RouteNotificationStatus.SENT);
        assertThat(jdbc.queryForObject(
                "select count(*) from route_arrival_alert_notification where status='SENT'", Integer.class))
                .isEqualTo(1);
    }

    // ── 발송 후 mark 전 crash → 재claim 발생 = at-least-once 한계 실증 ───────────────────

    @Test
    void crashAfterSendBeforeMark_reclaimAllowsDuplicate() {
        long id = seedPending(3100, "dep:dup", now().minusSeconds(1));
        stateService.claimBatch(now(), 10);
        // (여기서 토스 발송은 성공했다고 가정) mark 를 호출하지 않고 crash.
        jdbc.update("update route_arrival_alert_notification set processing_started_at = ? where id = ?",
                now().minusMinutes(properties.getClaimTimeoutMinutes() + 1), id);
        stateService.reclaim(now());

        // 재claim 이 성공한다 = 이미 보낸 알림이 한 번 더 나갈 수 있음(제거 불가, 문서화 대상).
        assertThat(stateService.claimBatch(now(), 10)).containsExactly(id);
        assertThat(attemptOf(id)).isEqualTo(2);
    }

    // ── dedup_key unique ─────────────────────────────────────────────────────────

    @Test
    void dedupKey_uniqueViolation() {
        long firstAlertId = tx.execute(s ->
                notificationRepository.findById(seedPending(3200, "dep:same", now())).orElseThrow().getAlert().getId());
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            RouteArrivalAlert a = alertRepository.findById(firstAlertId).orElseThrow();
            RouteArrivalAlertNotification dup = RouteArrivalAlertNotification.builder()
                    .alert(a).type(RouteNotificationType.CHANGE).dedupKey("dep:same")
                    .scheduledAt(now()).targetLeaveByAt(now()).payload(null).build();
            notificationRepository.saveAndFlush(dup);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── stale 정리: 부모 비-ACTIVE 인 PENDING → CANCELED, claim 대상서 제외 ──────────────

    @Test
    void cancelStaleDispatchable_inactiveParentCanceled() {
        long id = seedPending(3300, "dep:stale", now().minusSeconds(1));
        // 부모 CANCELED 로 전이(수정/취소 모사).
        tx.executeWithoutResult(s -> {
            RouteArrivalAlert a = notificationRepository.findById(id).orElseThrow().getAlert();
            a.markCanceled();
            alertRepository.save(a);
        });

        List<Long> claimed = stateService.claimBatch(now(), 10);
        assertThat(claimed).isEmpty();                                   // EXISTS(ACTIVE) 로 claim 제외
        assertThat(statusOf(id)).isEqualTo(RouteNotificationStatus.CANCELED); // cancelStale 이 정리
    }
}
