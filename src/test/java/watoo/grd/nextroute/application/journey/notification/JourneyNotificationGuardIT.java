package watoo.grd.nextroute.application.journey.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
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
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;
import watoo.grd.nextroute.testsupport.AbstractJourneyOutboxPostgresIT;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발송 직전 재확인이 무엇을 막는지.
 *
 * <p>계산한 순간과 보내는 순간 사이에 세상이 바뀐다. lease 가 끝났거나, 사용자 명령으로 상황이
 * 달라졌거나, 기다리던 차를 이미 놓쳤거나 이미 탔을 수 있다. 그때 알림을 그대로 보내면 사용자는
 * 지금과 어긋난 안내를 받는다.
 *
 * <p><b>안 막는 것도 똑같이 중요하다.</b> 평가 번호는 ETA 숫자만 바뀌어도 오르므로 취소 근거가
 * 아니고, 위험 알림은 환승 상태로 거르지 않는다 — 그러면 놓침 알림이 자기 자신을 지운다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, JourneyNotificationOutboxStateService.class,
        JourneyNotificationRelayService.class, JourneyNotificationMetrics.class,
        JourneyNotificationGuardIT.Config.class})
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
        "classpath:db/migration/V65__journey_leg_alight_door_side.sql"
},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("발송 직전 재확인")
class JourneyNotificationGuardIT extends AbstractJourneyOutboxPostgresIT {

    private static final Instant NOW = Instant.parse("2026-08-06T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 환승 대기 단계. 이 값보다 뒤로 가면 "이미 탔다"는 뜻이다. */
    private static final int WAIT_LEG = 2;

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
    @Autowired TransferConnectionRepository transferRepository;
    @Autowired JourneyNotificationRelayService relayService;
    @Autowired CountingNotificationSender sender;

    private long journeyId;

    @BeforeEach
    void setUp() {
        sender.reset();
        jdbcTemplate.update("delete from journey_notification");
        jdbcTemplate.update("delete from transfer_connection");
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

    // ── lease ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("lease 가 끝났으면 보내지 않는다")
    void expiredLeaseIsNotSent() {
        long id = enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK);
        jdbcTemplate.update("update journey_session set guidance_lease_until = ? where id = ?",
                Timestamp.from(NOW.minusSeconds(1)), journeyId);

        relayService.drainUntilEmpty();

        assertThat(sender.count())
                .as("신호가 끊긴 사람에게 가는 알림은 늦은 알림이다")
                .isZero();
        assertThat(statusOf(id)).isEqualTo("CANCELED");
        assertThat(reasonOf(id)).isEqualTo("lease-expired");
    }

    @Test
    @DisplayName("lease 가 살아 있으면 보낸다")
    void liveLeaseIsSent() {
        long id = enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK);

        relayService.drainUntilEmpty();

        assertThat(sender.count()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("SENT");
    }

    // ── version ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("명령 번호가 오르면 보내지 않는다")
    void changedCommandVersionIsNotSent() {
        long id = enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK);
        jdbcTemplate.update(
                "update journey_session set command_version = command_version + 1 where id = ?",
                journeyId);

        relayService.drainUntilEmpty();

        assertThat(sender.count()).isZero();
        assertThat(statusOf(id)).isEqualTo("CANCELED");
        assertThat(reasonOf(id)).isEqualTo("command-changed");
    }

    @Test
    @DisplayName("평가 번호만 올랐으면 그대로 보낸다")
    void changedEvaluationVersionStillSends() {
        long id = enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK);
        jdbcTemplate.update(
                "update journey_session set evaluation_version = evaluation_version + 1 where id = ?",
                journeyId);

        relayService.drainUntilEmpty();

        assertThat(sender.count())
                .as("평가 번호는 ETA 가 몇 초 흔들리기만 해도 오른다. 이걸로 취소하면 정상 알림이 "
                        + "발송 직전에 대거 죽는다")
                .isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("SENT");
    }

    // ── 접근 알림의 뜻 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("기다리던 차를 이미 놓쳤으면 접근 알림을 보내지 않는다")
    void missedTransferCancelsApproach() {
        seedTransfer(TransferStatus.MISSED);
        long id = enqueue(JourneyCheckpoint.TRANSFER_TARGET_APPROACHING);

        relayService.drainUntilEmpty();

        assertThat(sender.count())
                .as("지나간 차를 두고 곧 온다고 하면 거짓말이다")
                .isZero();
        assertThat(reasonOf(id)).isEqualTo("transfer-missed");
    }

    @Test
    @DisplayName("이미 탔으면 접근 알림을 보내지 않는다")
    void boardedUserCancelsApproach() {
        seedTransfer(TransferStatus.CATCHABLE);
        long id = enqueue(JourneyCheckpoint.TRANSFER_TARGET_APPROACHING);
        jdbcTemplate.update("update journey_session set current_leg_index = ? where id = ?",
                WAIT_LEG + 1, journeyId);

        relayService.drainUntilEmpty();

        assertThat(sender.count()).isZero();
        assertThat(reasonOf(id)).isEqualTo("already-boarded");
    }

    @Test
    @DisplayName("아직 기다리는 중이면 접근 알림을 보낸다")
    void waitingUserStillGetsApproach() {
        seedTransfer(TransferStatus.AT_RISK);
        jdbcTemplate.update("update journey_session set current_leg_index = ? where id = ?",
                WAIT_LEG, journeyId);
        long id = enqueue(JourneyCheckpoint.TRANSFER_TARGET_APPROACHING);

        relayService.drainUntilEmpty();

        assertThat(sender.count())
                .as("정류장에 서 있으면 AT_RISK 는 위험이 아니라 탈 준비 신호다")
                .isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("환승 행이 사라졌으면 접근 알림을 보내지 않는다")
    void missingTransferRowCancelsApproach() {
        long id = enqueue(JourneyCheckpoint.TRANSFER_TARGET_APPROACHING);

        relayService.drainUntilEmpty();

        assertThat(sender.count())
                .as("근거가 사라진 알림이다")
                .isZero();
        assertThat(reasonOf(id)).isEqualTo("transfer-missed");
    }

    @Test
    @DisplayName("놓침 알림은 환승이 MISSED 여도 나간다 — 자기 자신을 지우면 안 된다")
    void missedAlertIsNotFilteredByItsOwnStatus() {
        seedTransfer(TransferStatus.MISSED);
        long id = enqueue(JourneyCheckpoint.TRANSFER_RISK_MISSED);

        relayService.drainUntilEmpty();

        assertThat(sender.count())
                .as("가장 중요한 알림이 자기 상태 때문에 사라지면 안 된다")
                .isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("이미 탄 사용자에게도 놓침 알림은 나간다 — 상태 확인은 접근 알림 전용이다")
    void riskAlertIgnoresBoardingState() {
        seedTransfer(TransferStatus.MISSED);
        jdbcTemplate.update("update journey_session set current_leg_index = ? where id = ?",
                WAIT_LEG + 1, journeyId);
        long id = enqueue(JourneyCheckpoint.TRANSFER_RISK_MISSED);

        relayService.drainUntilEmpty();

        assertThat(sender.count()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("SENT");
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private void seedTransfer(TransferStatus status) {
        transferRepository.saveAndFlush(TransferConnection.builder()
                .journeyId(journeyId)
                .routeRevision(1)
                .transferIndex(0)
                .fromLegIndex(1)
                .walkLegIndex(null)
                .toLegIndex(WAIT_LEG)
                .status(status)
                .build());
    }

    private long enqueue(JourneyCheckpoint checkpoint) {
        String key = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject("""
                insert into journey_notification
                    (created_at, updated_at, journey_id, user_id, route_revision,
                     command_version, evaluation_version, scope_type, scope_id, checkpoint,
                     channel, logical_checkpoint_key, delivery_key, status,
                     scheduled_at, expires_at, attempt_count, requeue_count)
                select now(), now(), s.id, s.user_id, s.route_revision, s.command_version,
                       s.evaluation_version, 'TRANSFER', 0, ?, 'PUSH',
                       ?, ? || ':PUSH', 'PENDING', ?, ?, 0, 0
                from journey_session s where s.id = ?
                returning id
                """, Long.class, checkpoint.name(), key, key,
                Timestamp.from(NOW.minusSeconds(1)), Timestamp.from(NOW.plusSeconds(600)),
                journeyId);
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
}
