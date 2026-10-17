package watoo.grd.nextroute.application.journey.notification;

import org.springframework.test.context.TestPropertySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import watoo.grd.nextroute.application.journey.observability.JourneyNotificationMetrics;
import watoo.grd.nextroute.application.journey.service.JourneyNotificationOutboxStateService;
import watoo.grd.nextroute.application.journey.service.JourneyRetryBackoff;
import watoo.grd.nextroute.application.journey.service.JourneyNotificationRelayService;
import watoo.grd.nextroute.application.journey.dto.ClaimedNotification;
import watoo.grd.nextroute.application.journey.dto.SendTask;
import watoo.grd.nextroute.application.journey.exception.StaleClaimException;
import watoo.grd.nextroute.application.journey.service.JourneyNotificationLeaseKeeper;
import watoo.grd.nextroute.application.journey.service.JourneyRelayWaker;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey.JourneyNotificationRelayLoop;
import watoo.grd.nextroute.common.config.JpaConfig;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.testsupport.AbstractJourneyOutboxPostgresIT;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * relay 가 무엇을 보내고 무엇을 안 보내는지.
 *
 * <p>안 보내는 쪽이 더 중요하다. 끝난 여정·바뀐 경로·만료된 알림을 그대로 보내면 사용자는 지금
 * 상황과 어긋난 안내를 받는다. 그 판단은 전부 발송 <b>직전</b> 재확인이 한다 — claim 과 발송
 * 사이에 상황이 바뀌기 때문이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, JourneyNotificationOutboxStateService.class, JourneyRetryBackoff.class,
        JourneyNotificationRelayService.class, JourneyNotificationMetrics.class,
        watoo.grd.nextroute.application.journey.service.JourneyNotificationLeaseKeeper.class,
        watoo.grd.nextroute.application.journey.service.JourneyRelayWaker.class,
        JourneyNotificationRelayIT.Config.class})
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
        "classpath:db/migration/V63__journey_leg_guidance_info.sql",
        "classpath:db/migration/V65__journey_leg_alight_door_side.sql",
        "classpath:db/migration/V66__journey_session_evaluation_urgent.sql",
        "classpath:db/migration/V67__journey_reevaluation_trigger.sql"
},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
// 고정 백오프(60초 × 3회) 기대값으로 쓴 시험이다. EXPONENTIAL 기본(#451 후속)은 JourneyNotificationRetryPolicyTest 가 진다.
@TestPropertySource(properties = "journey.notification.retry-policy=FIXED")
@DisplayName("relay 는 아직 유효한 알림만 보낸다")
class JourneyNotificationRelayIT extends AbstractJourneyOutboxPostgresIT {

    private static final Instant NOW = Instant.parse("2026-08-06T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        JourneyNotificationProperties journeyNotificationProperties() {
            return new JourneyNotificationProperties();
        }

        @Bean
        CountingNotificationSender sender() {
            return new CountingNotificationSender();
        }
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JourneySessionRepository sessionRepository;
    @Autowired JourneyNotificationRelayService relayService;
    @Autowired JourneyNotificationOutboxStateService stateService;
    @Autowired CountingNotificationSender sender;
    @Autowired JourneyNotificationProperties properties;
    @Autowired MeterRegistry meterRegistry;
    @Autowired JourneyNotificationLeaseKeeper leaseKeeper;
    @Autowired JourneyRelayWaker relayWaker;
    @Autowired JourneyNotificationMetrics metrics;

    private long journeyId;

    @BeforeEach
    void setUp() {
        sender.reset();
        jdbcTemplate.update("delete from journey_notification");
        jdbcTemplate.update("delete from journey_session");
        jdbcTemplate.update("delete from users");
        long userId = jdbcTemplate.queryForObject(
                "insert into users (created_at, updated_at) values (now(), now()) returning id",
                Long.class);
        journeyId = sessionRepository.saveAndFlush(JourneySession.builder()
                .userId(userId)
                .startedAt(NOW)
                .guidanceLeaseUntil(NOW.plusSeconds(180))
                .routeSnapshot(MAPPER.createObjectNode().put("pathType", 1))
                .build()).getId();
    }

    @Test
    @DisplayName("정상 알림은 보내고 SENT 로 끝난다")
    void sendsPendingNotification() {
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(600));

        relayService.drainUntilEmpty();

        assertThat(sender.count()).isEqualTo(1);
        assertThat(sender.sent().get(0).notificationId()).isEqualTo(id);
        assertThat(statusOf(id)).isEqualTo("SENT");
        assertThat(sentAtOf(id)).isNotNull();
    }

    @Test
    @DisplayName("적재부터 발송까지 걸린 시간을 적재 때 쓴 clock 의 시간대로 잰다")
    void recordsDeliveryLagFromCreatedAt() {
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(600));
        // 적재기는 created_at 을 LocalDateTime.now(clock) 으로 찍는다(시간대 없는 칼럼). 같은 방식으로
        // 7초 전 KST 벽시계 시각을 넣는다. UTC 로 되돌리면 9시간이 어긋나 이 검증이 깨진다.
        LocalDateTime createdAt = LocalDateTime.ofInstant(NOW.minusSeconds(7), ZoneId.of("Asia/Seoul"));
        jdbcTemplate.update("update journey_notification set created_at = ? where id = ?",
                Timestamp.valueOf(createdAt), id);
        Timer lag = meterRegistry.timer(JourneyNotificationMetrics.DELIVERY_LAG, "channel", "PUSH");
        long before = lag.count();
        double beforeSeconds = lag.totalTime(TimeUnit.SECONDS);

        relayService.drainUntilEmpty();

        assertThat(statusOf(id)).isEqualTo("SENT");
        assertThat(lag.count() - before).isEqualTo(1);
        assertThat(lag.totalTime(TimeUnit.SECONDS) - beforeSeconds).isEqualTo(7.0);
    }

    @Test
    @DisplayName("일시 실패는 백오프만큼 미뤄 다시 대기로 돌아간다")
    void transientFailureGoesBackToPending() {
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(600));
        sender.failTransiently("provider 5xx");

        relayService.drainUntilEmpty();

        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(scheduledAtOf(id))
                .isEqualTo(NOW.plusSeconds(properties.getBackoffSeconds()));
        assertThat(attemptCountOf(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("재시도를 다 쓰면 DEAD 로 끝난다")
    void exhaustedRetriesBecomeDead() {
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(1800));
        jdbcTemplate.update("update journey_notification set attempt_count = ? where id = ?",
                properties.getMaxAttempts() - 1, id);
        sender.failTransiently("provider 5xx");

        relayService.drainUntilEmpty();

        assertThat(statusOf(id)).isEqualTo("DEAD");
    }

    @Test
    @DisplayName("영구 실패는 재시도 없이 FAILED 다")
    void permanentFailureIsFinal() {
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(600));
        sender.failPermanently("token 없음");

        relayService.drainUntilEmpty();

        assertThat(statusOf(id)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("발송기가 보내지 않기로 하면 실패가 아니라 사유를 남긴 취소다(#428)")
    void skippedIsCanceledWithReason() {
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(600));
        sender.skip("quiet-skipped-android");

        relayService.drainUntilEmpty();

        assertThat(statusOf(id))
                .as("재시도하면 같은 기기에 같은 판단이 나온다 — 실패로 남기면 지표가 거짓말을 한다")
                .isEqualTo("CANCELED");
        assertThat(reasonOf(id)).isEqualTo("quiet-skipped-android");
    }

    @Test
    @DisplayName("계약에 없는 예외도 일시 실패로 분류돼 결국 DEAD 로 끝난다")
    void unexpectedExceptionDoesNotLoopForever() {
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(1800));
        jdbcTemplate.update("update journey_notification set attempt_count = ? where id = ?",
                properties.getMaxAttempts() - 1, id);
        sender.throwUnexpected();

        relayService.drainUntilEmpty();

        assertThat(statusOf(id))
                .as("안 잡으면 PROCESSING 에 남아 회수 → 재시도 → 또 예외를 영원히 반복한다")
                .isEqualTo("DEAD");
    }

    @Test
    @DisplayName("만료된 알림은 보내지 않고 취소한다")
    void expiredNotificationIsCanceled() {
        long id = enqueue(NOW.minusSeconds(600), NOW.minusSeconds(1));

        relayService.drainUntilEmpty();

        assertThat(sender.count()).isZero();
        assertThat(statusOf(id))
                .as("늦게 도착한 환승 알림은 사용자를 엉뚱한 때에 움직인다. 실패가 아니라 취소다")
                .isEqualTo("CANCELED");
        assertThat(reasonOf(id)).isEqualTo("expired");
    }

    @Test
    @DisplayName("놓침 알림은 도착 시각이 과거여도 실제로 나간다")
    void missedNotificationStillGoesOut() {
        // 만료 바닥(180초)이 지켜 주는 상황을 그대로 재현한다.
        long id = enqueue(NOW.minusSeconds(1), NOW.plus(properties.minTtl()));

        relayService.drainUntilEmpty();

        assertThat(sender.count()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("여정이 끝났으면 보내지 않는다")
    void endedJourneyIsNotSent() {
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(600));
        jdbcTemplate.update("update journey_session set status = 'CANCELED' where id = ?", journeyId);

        relayService.drainUntilEmpty();

        assertThat(sender.count()).isZero();
        assertThat(statusOf(id)).isEqualTo("CANCELED");
        assertThat(reasonOf(id)).isEqualTo("journey-ended");
    }

    @Test
    @DisplayName("경로가 바뀌었으면 보내지 않는다")
    void changedRouteRevisionIsNotSent() {
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(600));
        jdbcTemplate.update("update journey_session set route_revision = 2 where id = ?", journeyId);

        relayService.drainUntilEmpty();

        assertThat(sender.count()).isZero();
        assertThat(statusOf(id))
                .as("재탐색 뒤 이전 경로의 알림이 나가면 사용자는 없어진 환승을 안내받는다")
                .isEqualTo("CANCELED");
        assertThat(reasonOf(id)).isEqualTo("route-changed");
    }

    @Test
    @DisplayName("재시도 시각이 만료를 넘으면 재시도 대신 취소한다")
    void retryPastExpiryIsCanceled() {
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(30));
        sender.failTransiently("provider 5xx");

        relayService.drainUntilEmpty();

        assertThat(statusOf(id)).isEqualTo("CANCELED");
        assertThat(reasonOf(id)).isEqualTo("expired-before-retry");
    }

    @Test
    @DisplayName("이미 취소된 행은 일시 실패 기록으로 되살아나지 않는다")
    void canceledRowIsNotRevivedByFailureMark() {
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(600));
        ClaimedNotification claim = stateService.claimBatch(NOW, 10).get(0);
        jdbcTemplate.update("update journey_notification set status = 'CANCELED' where id = ?", id);

        assertThatThrownBy(() -> stateService.markTransientFailure(claim, "늦게 도착한 실패 기록"))
                .as("늦은 기록은 0행 → 예외로 같은 트랜잭션을 되돌린다(#451)")
                .isInstanceOf(StaleClaimException.class);

        assertThat(statusOf(id))
                .as("되살리면 죽은 여정의 알림이 나간다")
                .isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("아직 시각이 안 된 행은 집어 가지 않는다")
    void futureScheduledIsNotClaimed() {
        long id = enqueue(NOW.plusSeconds(60), NOW.plusSeconds(600));

        relayService.drainUntilEmpty();

        assertThat(sender.count()).isZero();
        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("발송 보장은 최소 한 번이다 — 보낸 직후 죽으면 회수 뒤 다시 간다")
    void atLeastOnceDeliveryIsTheContract() {
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(1800));

        // 워커가 provider 에 넘긴 <b>직후</b>, 기록하기 <b>전에</b> 죽은 상황.
        ClaimedNotification claim = stateService.claimBatch(NOW, 10).get(0);
        sender.send(stateService.loadTask(claim).orElseThrow());

        // 그 워커가 돌아오지 않아 행이 PROCESSING 에 고착됐다. 회수 기준은 DB now() 라(#451) 행이
        // 집힌 시각을 DB 시계로 회수 기준보다 앞으로 돌려 같은 상태를 만든다.
        makeStale(id);
        stateService.reclaim(NOW);
        relayService.drainUntilEmpty();

        assertThat(sender.count())
                .as("정확히 한 번을 만들려면 provider 응답과 DB 커밋을 원자적으로 묶어야 하는데 "
                        + "불가능하다. 버그가 아니라 계약이다")
                .isEqualTo(2);
        assertThat(statusOf(id)).isEqualTo("SENT");
    }

    // ── #451 증서·heartbeat·PULL ────────────────────────────────────────────

    @Test
    @DisplayName("heartbeat 중인 행은 회수 기준이 지나도 회수되지 않고, heartbeat 가 멈추면 회수된다")
    void heartbeatKeepsRowAliveUntilItStops() {
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(1800));
        ClaimedNotification claim = stateService.claimBatch(NOW, 10).get(0);
        leaseKeeper.hold(claim);
        leaseKeeper.beat(); // 첫 주기는 회수 문이 닫혀 있다 — 직전 성공이 없다

        makeStale(id);
        assertThat(leaseKeeper.beat()).as("heartbeat 가 먼저 돌아 이 행을 지금으로 민다").isZero();
        assertThat(statusOf(id)).isEqualTo("PROCESSING");

        leaseKeeper.release(claim); // 워커가 죽었다 = 더는 heartbeat 가 없다
        makeStale(id);
        assertThat(leaseKeeper.beat()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(intOf("requeue_count", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("직전 heartbeat 성공이 회수 기준/3 보다 오래됐으면(DB 정지 직후) 그 주기는 회수하지 않는다")
    void reclaimGateClosesAfterLongGap() {
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(1800));
        stateService.claimBatch(NOW, 10);
        makeStale(id);
        java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
        JourneyNotificationLeaseKeeper keeper = newKeeper(nanos::get);

        keeper.beat();
        nanos.addAndGet(properties.claimTimeout().dividedBy(3).plusSeconds(1).toNanos());
        assertThat(keeper.beat()).as("남의 행도 heartbeat 를 못 했을 수 있다").isZero();
        assertThat(statusOf(id)).isEqualTo("PROCESSING");

        nanos.addAndGet(java.time.Duration.ofSeconds(10).toNanos());
        assertThat(keeper.beat()).as("다음 주기엔 문이 열린다").isEqualTo(1);
    }

    @Test
    @DisplayName("회수된 행은 원래 예정 시각을 지켜 뒤에 들어온 행보다 먼저 다시 집힌다(#451 후속)")
    void reclaimedRowKeepsItsPlaceInLine() {
        long early = enqueue(NOW.minusSeconds(60), NOW.plusSeconds(1800));
        ClaimedNotification claim = stateService.claimBatch(NOW, 1).get(0);
        assertThat(claim.id()).isEqualTo(early);
        long later = enqueue(NOW.minusSeconds(10), NOW.plusSeconds(1800));

        makeStale(early);
        stateService.reclaim(NOW);

        assertThat(scheduledAtOf(early))
                .as("now 로 덮으면 회수된 행이 줄 맨 뒤로 가 회수 뒤에도 수십 초를 더 기다린다(④a' 45초 회수 → 85~94초 접수)")
                .isEqualTo(NOW.minusSeconds(60));
        assertThat(stateService.claimBatch(NOW, 1).get(0).id()).isEqualTo(early);
        assertThat(statusOf(later)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("회수된 행에 옛 워커의 접수 기록은 0행이고 새 증서의 기록만 남는다")
    void staleSentAfterReclaimIsDropped() {
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(1800));
        ClaimedNotification old = stateService.claimBatch(NOW, 10).get(0);
        makeStale(id);
        stateService.reclaim(NOW);
        ClaimedNotification fresh = stateService.claimBatch(NOW, 10).get(0);
        assertThat(fresh.token()).isEqualTo(old.token() + 1);

        assertThatThrownBy(() -> stateService.markSent(old)).isInstanceOf(StaleClaimException.class);
        assertThat(stateService.loadTask(old)).as("옛 증서로는 발송 직전 재확인도 통과 못 한다").isEmpty();
        assertThat(statusOf(id)).isEqualTo("PROCESSING");

        stateService.markSent(fresh);
        assertThat(statusOf(id)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("재확인 결과로 접수를 기록해도(행 재조회 없음) 옛 증서는 0행이고 새 증서만 SENT 가 된다(#461 후속)")
    void staleSendTaskAfterReclaimIsDropped() {
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(1800));
        ClaimedNotification old = stateService.claimBatch(NOW, 10).get(0);
        SendTask oldTask = stateService.loadSendTask(old).orElseThrow();
        makeStale(id);
        stateService.reclaim(NOW);
        ClaimedNotification fresh = stateService.claimBatch(NOW, 10).get(0);
        SendTask freshTask = stateService.loadSendTask(fresh).orElseThrow();

        assertThatThrownBy(() -> stateService.markSent(old, oldTask)).isInstanceOf(StaleClaimException.class);
        assertThat(statusOf(id)).isEqualTo("PROCESSING");

        stateService.markSent(fresh, freshTask);
        assertThat(statusOf(id)).isEqualTo("SENT");
        assertThat(freshTask.command().notificationId()).isEqualTo(id);
        assertThat(freshTask.createdAt()).as("적재 시각을 같이 읽어 와야 지연 지표를 잰다").isNotNull();
    }

    @Test
    @DisplayName("시도 상한은 회수 몫을 뺀 실제 발송 시도로 센다")
    void attemptsCapExcludesRequeues() {
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(1800));
        // 워커가 한 번 죽어 회수된 뒤 지금 마지막 시도라고 보이는 행: attempt 2 → claim 이 3 으로, requeue 1.
        jdbcTemplate.update("update journey_notification set attempt_count = ?, requeue_count = 1 where id = ?",
                properties.getMaxAttempts() - 1, id);
        sender.failTransiently("provider 5xx");

        relayService.drainUntilEmpty();

        assertThat(statusOf(id))
                .as("실제 발송 시도는 2번뿐이다 — 회수를 시도로 세면 워커 장애가 알림을 DEAD 로 만든다")
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("PULL: 한 워커의 발송이 막혀도 다른 워커가 나머지를 보낸다")
    void pullWorkersAreIndependent() throws Exception {
        long blocked = enqueue(NOW.minusSeconds(10), NOW.plusSeconds(1800));
        List<Long> others = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            others.add(enqueue(NOW.minusSeconds(5), NOW.plusSeconds(1800)));
        }
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        sender.behave(command -> {
            if (command.notificationId() == blocked) {
                try {
                    gate.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        properties.getRelay().setMode(JourneyNotificationProperties.RelayMode.PULL);
        properties.getRelay().setPullWorkers(3);
        properties.getRelay().setPullClaimSize(1);
        JourneyNotificationRelayLoop loop = new JourneyNotificationRelayLoop(relayService, relayWaker, properties,
                leaseKeeper, new org.springframework.mock.env.MockEnvironment());
        try {
            loop.start();
            relayWaker.wake();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline
                    && others.stream().anyMatch(o -> !"SENT".equals(statusOf(o)))) {
                Thread.sleep(50);
            }
            assertThat(others).allSatisfy(o -> assertThat(statusOf(o)).isEqualTo("SENT"));
            assertThat(statusOf(blocked)).as("막힌 한 건만 PROCESSING 에 남는다").isEqualTo("PROCESSING");
            assertThat(leaseKeeper.inFlightCount()).as("막힌 행은 heartbeat 대상이다").isEqualTo(1);
        } finally {
            gate.countDown();
            loop.stop();
            properties.getRelay().setMode(JourneyNotificationProperties.RelayMode.BATCH);
            properties.getRelay().setPullWorkers(32);
            properties.getRelay().setPullClaimSize(2);
        }
        assertThat(statusOf(blocked)).as("종료는 진행 중 기록을 마무리한 뒤다").isEqualTo("SENT");
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    /** 집힌 시각을 DB 시계로 회수 기준보다 앞으로 돌린다. 시험 시계(2026-08-06 고정)와 DB 시계가 다르다. */
    private void makeStale(long id) {
        jdbcTemplate.update("update journey_notification set processing_started_at = now() - make_interval(secs => ?)"
                + " where id = ?", properties.claimTimeout().toSeconds() + 1.0, id);
    }

    private JourneyNotificationLeaseKeeper newKeeper(java.util.function.LongSupplier nanoTime) {
        try {
            var ctor = JourneyNotificationLeaseKeeper.class.getDeclaredConstructor(
                    JourneyNotificationOutboxStateService.class, JourneyNotificationProperties.class,
                    JourneyNotificationMetrics.class, Clock.class, java.util.function.LongSupplier.class);
            ctor.setAccessible(true);
            return ctor.newInstance(stateService, properties, metrics, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")),
                    nanoTime);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private int intOf(String column, long id) {
        return jdbcTemplate.queryForObject(
                "select " + column + " from journey_notification where id = ?", Integer.class, id);
    }

    private long enqueue(Instant scheduledAt, Instant expiresAt) {
        String key = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject("""
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
                Timestamp.from(scheduledAt), Timestamp.from(expiresAt), journeyId);
    }

    private String statusOf(long id) {
        return jdbcTemplate.queryForObject(
                "select status from journey_notification where id = ?", String.class, id);
    }

    private String reasonOf(long id) {
        return jdbcTemplate.queryForObject(
                "select last_failure_reason from journey_notification where id = ?",
                String.class, id);
    }

    private Instant scheduledAtOf(long id) {
        return jdbcTemplate.queryForObject(
                "select scheduled_at from journey_notification where id = ?", Instant.class, id);
    }

    private Instant sentAtOf(long id) {
        return jdbcTemplate.queryForObject(
                "select sent_at from journey_notification where id = ?", Instant.class, id);
    }

    private int attemptCountOf(long id) {
        return jdbcTemplate.queryForObject(
                "select attempt_count from journey_notification where id = ?", Integer.class, id);
    }
}
