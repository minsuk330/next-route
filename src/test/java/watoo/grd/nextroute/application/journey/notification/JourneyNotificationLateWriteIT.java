package watoo.grd.nextroute.application.journey.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.ClaimedNotification;
import watoo.grd.nextroute.application.journey.exception.NotificationSendException;
import watoo.grd.nextroute.application.journey.exception.StaleClaimException;
import watoo.grd.nextroute.application.journey.observability.JourneyNotificationMetrics;
import watoo.grd.nextroute.application.journey.service.JourneyNotificationLeaseKeeper;
import watoo.grd.nextroute.application.journey.service.JourneyNotificationOutboxStateService;
import watoo.grd.nextroute.application.journey.service.JourneyNotificationRelayService;
import watoo.grd.nextroute.application.journey.service.JourneyRelayWaker;
import watoo.grd.nextroute.application.journey.service.JourneyRetryBackoff;
import watoo.grd.nextroute.common.config.JpaConfig;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.testsupport.AbstractJourneyOutboxPostgresIT;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 늦게 도착한 기록은 증서로 막는다 — 그 장면을 결정적으로 재현한다.
 *
 * <p>장면: 발송기 A 가 행을 집어(증서 1) FCM 에 접수시킨 뒤 기록 직전에 멈춘다(GC 정지·네트워크 단절). heartbeat 가
 * 끊겨 다른 발송기가 회수하고 B 가 다시 집어(증서 2) 보낸다. 그 뒤 A 가 깨어나 늦게 기록한다.
 *
 * <p>부하 실험의 강제 종료(kill -9)는 A 를 죽이므로 이 장면이 생기지 않는다. 그래서 실험의 "중복 0"만으로는 증서가
 * 일한 것인지 알 수 없다. 여기서 래치로 순서를 고정해 증서가 무엇을 막는지 확인한다.
 *
 * <p><b>증서가 막는 것과 못 막는 것.</b> A 와 B 는 둘 다 FCM 에 보낸다 — 발송은 "최소 한 번"이다. 증서가 막는 건 늦은
 * 기록이 상태를 덮는 것이다: B 가 보내는 중인 행을 A 가 SENT·PENDING(재시도)·FAILED 로 바꾸거나, 울릴 권리를 반납해
 * 이미 나간 울림 뒤에 또 울리게 하는 것.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, JourneyNotificationOutboxStateService.class, JourneyRetryBackoff.class,
        JourneyNotificationRelayService.class, JourneyNotificationMetrics.class,
        JourneyNotificationLeaseKeeper.class, JourneyRelayWaker.class,
        JourneyNotificationLateWriteIT.Config.class})
@Sql(scripts = {
        "classpath:db/it/journey-reset.sql",
        "classpath:db/migration/V35__create_journey.sql",
        "classpath:db/migration/V36__add_journey_transit_resolution.sql",
        "classpath:db/migration/V37__align_journey_navigation_contract.sql",
        "classpath:db/migration/V38__add_journey_last_progress_id.sql",
        "classpath:db/migration/V39__create_user_push_token.sql",
        "classpath:db/migration/V68__user_push_token_quiet_update.sql",
        "classpath:db/migration/V40__create_journey_notification.sql",
        "classpath:db/migration/V70__journey_notification_priority.sql",
        "classpath:db/migration/V53__add_reroute_alert_state.sql",
        "classpath:db/migration/V59__journey_session_kind.sql",
        "classpath:db/migration/V73__journey_session_exclude_express.sql",
        "classpath:db/migration/V63__journey_leg_guidance_info.sql",
        "classpath:db/migration/V65__journey_leg_alight_door_side.sql",
        "classpath:db/migration/V71__journey_leg_train_kind.sql",
        "classpath:db/migration/V66__journey_session_evaluation_urgent.sql",
        "classpath:db/migration/V67__journey_reevaluation_trigger.sql",
        "classpath:db/migration/V69__journey_session_alert_claim.sql"
},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("FCM 접수 뒤 기록 전에 멈춘 발송기의 늦은 기록")
class JourneyNotificationLateWriteIT extends AbstractJourneyOutboxPostgresIT {

    private static final Instant NOW = Instant.parse("2026-08-06T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long WAIT_SECONDS = 20;

    @TestConfiguration
    static class Config {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        HookedProperties journeyNotificationProperties() {
            return new HookedProperties();
        }

        @Bean
        CountingNotificationSender sender() {
            return new CountingNotificationSender();
        }
    }

    /**
     * 울릴 권리 반납 직전에 끼어들 자리. 반납은 {@code getFatigue().isEnabled()} 를 본 뒤에 일어나므로, 그 호출에
     * 한 번만 끼어들어 "재확인은 통과했는데 기록 UPDATE 는 아직"인 틈을 만든다.
     */
    static class HookedProperties extends JourneyNotificationProperties {

        final AtomicReference<Runnable> beforeRelease = new AtomicReference<>();
        private final Fatigue hooked = new Fatigue() {
            @Override
            public boolean isEnabled() {
                Runnable once = beforeRelease.getAndSet(null);
                if (once != null) {
                    once.run();
                }
                return super.isEnabled();
            }
        };

        @Override
        public Fatigue getFatigue() {
            return hooked;
        }
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JourneySessionRepository sessionRepository;
    @Autowired JourneyNotificationRelayService relayService;
    @Autowired JourneyNotificationOutboxStateService stateService;
    @Autowired CountingNotificationSender sender;
    @Autowired HookedProperties properties;
    @Autowired MeterRegistry meterRegistry;
    @Autowired JourneyNotificationLeaseKeeper leaseKeeper;

    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    /** 지표 등록소는 시험끼리 공유된다. 시험마다 시작 값을 빼고 본다. */
    private final java.util.Map<String, Double> staleBase = new java.util.HashMap<>();
    private long journeyId;

    @BeforeEach
    void setUp() {
        sender.reset();
        properties.beforeRelease.set(null);
        for (String op : new String[]{"sent", "transient", "permanent"}) {
            staleBase.put(op, staleCount(op));
        }
        properties.getFatigue().setEnabled(true);
        jdbcTemplate.update("delete from journey_notification");
        jdbcTemplate.update("delete from journey_session");
        jdbcTemplate.update("delete from users");
        long userId = jdbcTemplate.queryForObject(
                "insert into users (created_at, updated_at) values (now(), now()) returning id", Long.class);
        journeyId = sessionRepository.saveAndFlush(JourneySession.builder()
                .userId(userId)
                .startedAt(NOW)
                .guidanceLeaseUntil(NOW.plusSeconds(180))
                .routeSnapshot(MAPPER.createObjectNode().put("pathType", 1))
                .build()).getId();
    }

    @AfterEach
    void tearDown() {
        properties.getFatigue().setEnabled(false);
        properties.getRelay().setLoadtestUnfenced(false);
        workers.shutdownNow();
    }

    @Test
    @DisplayName("B 가 보내는 중에 도착한 A 의 늦은 접수 기록은 0행이고, 행은 B 의 것으로 남아 B 가 SENT 로 끝낸다")
    void lateSentWhileReclaimedRowIsInFlightIsDropped() throws Exception {
        long id = enqueueHoldingAlertClaim();
        Scene scene = Scene.bothBlocked(sender);

        Future<?> a = workers.submit(relayService::relayOnce);
        scene.awaitAAccepted();
        reclaimAsAnotherInstance(id);
        Future<?> b = workers.submit(relayService::relayOnce);
        scene.awaitBAccepted();
        assertThat(attemptCountOf(id)).as("B 가 다시 집어 증서가 2 가 됐다").isEqualTo(2);

        scene.releaseA();
        a.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(statusOf(id))
                .as("증서가 없으면 A 가 SENT 로 끝내 버리고, B 의 결과(실패면 재시도)가 기록될 자리가 사라진다")
                .isEqualTo("PROCESSING");
        assertThat(staleWrites("sent")).isEqualTo(1);
        assertThat(leaseKeeper.inFlightCount())
                .as("A 가 끝나며 B 의 heartbeat 대상까지 지우면 B 가 보내는 동안 또 회수된다")
                .isEqualTo(1);

        scene.releaseB();
        b.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(statusOf(id)).isEqualTo("SENT");
        assertThat(sender.count()).as("둘 다 FCM 에 접수됐다 — 증서는 발송 중복이 아니라 기록 중복을 막는다").isEqualTo(2);
        assertThat(staleWrites("sent")).isEqualTo(1);
        assertThat(alertClaimKey()).as("울릴 권리는 그대로").isEqualTo(logicalKeyOf(id));
    }

    @Test
    @DisplayName("B 가 보내는 중에 도착한 A 의 늦은 일시 실패는 행을 재시도 대기로 되돌리지 못한다")
    void lateTransientFailureCannotRequeueReclaimedRow() throws Exception {
        long id = enqueueHoldingAlertClaim();
        Scene scene = Scene.bothBlocked(sender);
        scene.failAAfterRelease(new NotificationSendException("provider 5xx", false));

        Future<?> a = workers.submit(relayService::relayOnce);
        scene.awaitAAccepted();
        reclaimAsAnotherInstance(id);
        Future<?> b = workers.submit(relayService::relayOnce);
        scene.awaitBAccepted();

        scene.releaseA();
        a.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(statusOf(id))
                .as("PENDING 으로 되돌리면 B 가 보낸 뒤에 한 번 더(세 번째) 나간다")
                .isEqualTo("PROCESSING");
        assertThat(staleWrites("transient")).isEqualTo(1);

        scene.releaseB();
        b.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(statusOf(id)).isEqualTo("SENT");
        assertThat(sender.count()).isEqualTo(1);
        assertThat(alertClaimKey()).isEqualTo(logicalKeyOf(id));
    }

    @Test
    @DisplayName("B 가 이미 SENT 로 끝낸 뒤 도착한 A 의 늦은 영구 실패는 FAILED 로 덮지도, 울릴 권리를 반납하지도 못한다")
    void latePermanentFailureAfterSentKeepsAlertClaim() throws Exception {
        long id = enqueueHoldingAlertClaim();
        Scene scene = Scene.onlyABlocked(sender);
        scene.failAAfterRelease(new NotificationSendException("fcm: UNREGISTERED", true));

        Future<?> a = workers.submit(relayService::relayOnce);
        scene.awaitAAccepted();
        reclaimAsAnotherInstance(id);
        relayService.relayOnce();
        assertThat(statusOf(id)).isEqualTo("SENT");

        scene.releaseA();
        a.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(statusOf(id)).isEqualTo("SENT");
        assertThat(staleWrites("permanent")).isEqualTo(1);
        assertThat(alertClaimKey())
                .as("반납하면 이미 울린 경로 버전에서 다음 악화가 또 울린다")
                .isEqualTo(logicalKeyOf(id));
    }

    @Test
    @DisplayName("부하 실험의 증서 끔(#464)에서는 같은 장면의 늦은 일시 실패가 행을 재시도 대기로 되돌린다(대조군)")
    void unfencedLateTransientFailureRequeuesReclaimedRow() throws Exception {
        properties.getRelay().setLoadtestUnfenced(true);
        double before = unfencedLateWrites("transient");
        long id = enqueueHoldingAlertClaim();
        Scene scene = Scene.bothBlocked(sender);
        scene.failAAfterRelease(new NotificationSendException("provider 5xx", false));

        Future<?> a = workers.submit(relayService::relayOnce);
        scene.awaitAAccepted();
        reclaimAsAnotherInstance(id);
        Future<?> b = workers.submit(relayService::relayOnce);
        scene.awaitBAccepted();

        scene.releaseA();
        a.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(statusOf(id)).as("B 가 보내는 중인데 A 의 기록이 행을 되돌렸다 → B 뒤에 세 번째 발송").isEqualTo("PENDING");
        assertThat(unfencedLateWrites("transient") - before).isEqualTo(1);
        assertThat(staleWrites("transient")).isZero();

        scene.releaseB();
        b.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("재확인과 기록 사이에 회수·재발송이 끼면 먼저 한 권리 반납까지 되돌린다")
    void raceBetweenRecheckAndWriteRollsBackAlertClaimRelease() {
        long id = enqueueHoldingAlertClaim();
        ClaimedNotification a = stateService.claimBatch(NOW, 10).get(0);
        AtomicInteger interleaved = new AtomicInteger();
        properties.beforeRelease.set(() -> runElsewhere(() -> {
            reclaimAsAnotherInstance(id);
            ClaimedNotification b = stateService.claimBatch(NOW, 10).get(0);
            stateService.markSent(b);
            interleaved.incrementAndGet();
        }));

        assertThatThrownBy(() -> stateService.markPermanentFailure(a, "fcm: UNREGISTERED"))
                .isInstanceOf(StaleClaimException.class);

        assertThat(interleaved).as("재확인은 증서 1 로 통과했고, 그 뒤에 B 가 끼어들었다").hasValue(1);
        assertThat(statusOf(id)).isEqualTo("SENT");
        assertThat(alertClaimKey())
                .as("반납 UPDATE 는 이미 실행됐다(이 행 자신은 빼고 세므로 비울 수 있었다). 알림 행 기록이 0행이라 함께 되돌려졌다")
                .isEqualTo(logicalKeyOf(id));
    }

    // ── 장면 ────────────────────────────────────────────────────────────────

    /**
     * 가짜 FCM. 첫 호출은 A, 둘째는 B. 각자 "접수됨"을 알린 뒤 풀어 줄 때까지 기록 직전에 멈춘다.
     * 접수 순간은 {@code send} 가 돌아오는 때다 — 막힌 동안은 아직 기록을 안 한 상태다.
     */
    private static final class Scene {

        private final CountDownLatch aAccepted = new CountDownLatch(1);
        private final CountDownLatch bAccepted = new CountDownLatch(1);
        private final CountDownLatch aRelease = new CountDownLatch(1);
        private final CountDownLatch bRelease = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private volatile RuntimeException aOutcome;

        static Scene bothBlocked(CountingNotificationSender sender) {
            Scene s = new Scene();
            sender.behave(command -> s.onSend(true));
            return s;
        }

        static Scene onlyABlocked(CountingNotificationSender sender) {
            Scene s = new Scene();
            sender.behave(command -> s.onSend(false));
            return s;
        }

        void failAAfterRelease(RuntimeException outcome) {
            this.aOutcome = outcome;
        }

        private void onSend(boolean blockB) {
            int n = calls.incrementAndGet();
            if (n == 1) {
                aAccepted.countDown();
                await(aRelease);
                if (aOutcome != null) {
                    throw aOutcome;
                }
            } else if (n == 2 && blockB) {
                bAccepted.countDown();
                await(bRelease);
            }
        }

        void awaitAAccepted() {
            await(aAccepted);
        }

        void awaitBAccepted() {
            await(bAccepted);
        }

        void releaseA() {
            aRelease.countDown();
        }

        void releaseB() {
            bRelease.countDown();
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("장면 순서가 어긋났다: 래치가 풀리지 않음");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    /**
     * 다른 인스턴스가 회수한 것처럼 만든다. A 의 heartbeat 가 끊긴 지 회수 기준을 넘긴 상태(processing_started_at 을 DB 시계로
     * 앞당김) → 회수.
     */
    private void reclaimAsAnotherInstance(long id) {
        jdbcTemplate.update("update journey_notification set processing_started_at = now() - make_interval(secs => ?)"
                + " where id = ?", properties.claimTimeout().toSeconds() + 1.0, id);
        assertThat(stateService.reclaim(NOW)).isEqualTo(1);
    }

    /** 다른 스레드(= 다른 커넥션)에서 돌리고 끝날 때까지 기다린다. 부르는 쪽 트랜잭션은 그동안 열려 있다. */
    private void runElsewhere(Runnable task) {
        try {
            workers.submit(task).get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 알림 한 행을 쌓고, 그 알림이 이 경로 버전의 울릴 권리를 쥔 상태로 둔다. */
    private long enqueueHoldingAlertClaim() {
        String key = UUID.randomUUID().toString();
        long id = jdbcTemplate.queryForObject("""
                insert into journey_notification
                    (created_at, updated_at, journey_id, user_id, route_revision,
                     command_version, evaluation_version, scope_type, scope_id, checkpoint,
                     channel, logical_checkpoint_key, delivery_key, status,
                     scheduled_at, expires_at, attempt_count, requeue_count)
                select now(), now(), s.id, s.user_id, s.route_revision, s.command_version,
                       s.evaluation_version, 'TRANSFER', 0, 'TRANSFER_RISK_AT_RISK', 'PUSH',
                       ?, ? || ':PUSH', 'PENDING', ?, ?, 0, 0
                from journey_session s where s.id = ?
                returning id
                """, Long.class, key, key,
                Timestamp.from(NOW.minusSeconds(1)), Timestamp.from(NOW.plusSeconds(1800)), journeyId);
        jdbcTemplate.update("update journey_session set alert_claim_revision = route_revision, alert_claim_key = ?"
                + " where id = ?", key, journeyId);
        return id;
    }

    private double staleWrites(String op) {
        return staleCount(op) - staleBase.getOrDefault(op, 0.0);
    }

    private double unfencedLateWrites(String op) {
        var counter = meterRegistry.find(JourneyNotificationMetrics.LOADTEST_UNFENCED_LATE_WRITE).tag("op", op).counter();
        return counter == null ? 0 : counter.count();
    }

    private double staleCount(String op) {
        var counter = meterRegistry.find(JourneyNotificationMetrics.STALE_WRITE).tag("op", op).counter();
        return counter == null ? 0 : counter.count();
    }

    private String alertClaimKey() {
        return jdbcTemplate.queryForObject("select alert_claim_key from journey_session where id = ?",
                String.class, journeyId);
    }

    private String logicalKeyOf(long id) {
        return jdbcTemplate.queryForObject(
                "select logical_checkpoint_key from journey_notification where id = ?", String.class, id);
    }

    private String statusOf(long id) {
        return jdbcTemplate.queryForObject(
                "select status from journey_notification where id = ?", String.class, id);
    }

    private int attemptCountOf(long id) {
        return jdbcTemplate.queryForObject(
                "select attempt_count from journey_notification where id = ?", Integer.class, id);
    }
}
