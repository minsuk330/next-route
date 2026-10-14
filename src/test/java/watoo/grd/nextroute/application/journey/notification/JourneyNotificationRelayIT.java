package watoo.grd.nextroute.application.journey.notification;

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
import watoo.grd.nextroute.application.journey.service.JourneyNotificationRelayService;
import watoo.grd.nextroute.common.config.JpaConfig;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.testsupport.AbstractJourneyOutboxPostgresIT;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
@Import({JpaConfig.class, JourneyNotificationOutboxStateService.class,
        JourneyNotificationRelayService.class, JourneyNotificationMetrics.class,
        JourneyNotificationRelayIT.Config.class})
@Sql(scripts = {
        "classpath:db/it/journey-reset.sql",
        "classpath:db/migration/V35__create_journey.sql",
        "classpath:db/migration/V36__add_journey_transit_resolution.sql",
        "classpath:db/migration/V37__align_journey_navigation_contract.sql",
        "classpath:db/migration/V38__add_journey_last_progress_id.sql",
        "classpath:db/migration/V39__create_user_push_token.sql",
        "classpath:db/migration/V40__create_journey_notification.sql",
        "classpath:db/migration/V53__add_reroute_alert_state.sql",
        "classpath:db/migration/V59__journey_session_kind.sql",
        "classpath:db/migration/V63__journey_leg_guidance_info.sql",
        "classpath:db/migration/V65__journey_leg_alight_door_side.sql",
        "classpath:db/migration/V66__journey_session_evaluation_urgent.sql",
        "classpath:db/migration/V67__journey_reevaluation_trigger.sql"
},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
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
        jdbcTemplate.update("update journey_notification set status = 'CANCELED' where id = ?", id);

        stateService.markTransientFailure(id, "늦게 도착한 실패 기록");

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
        stateService.claimBatch(NOW, 10);
        sender.send(stateService.loadTask(id).orElseThrow());

        // 그 워커가 돌아오지 않아 행이 PROCESSING 에 고착됐다. 시계가 고정이라 시간을 미는
        // 대신 행이 집힌 시각을 뒤로 돌려 같은 상태를 만든다.
        jdbcTemplate.update("update journey_notification set processing_started_at = ? where id = ?",
                Timestamp.from(NOW.minus(properties.claimTimeout()).minusSeconds(1)), id);
        stateService.reclaim(NOW);
        relayService.drainUntilEmpty();

        assertThat(sender.count())
                .as("정확히 한 번을 만들려면 provider 응답과 DB 커밋을 원자적으로 묶어야 하는데 "
                        + "불가능하다. 버그가 아니라 계약이다")
                .isEqualTo(2);
        assertThat(statusOf(id)).isEqualTo("SENT");
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

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
