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
import watoo.grd.nextroute.application.journey.port.out.FirebaseMessagingClient;
import watoo.grd.nextroute.application.journey.port.out.NotificationSender;
import watoo.grd.nextroute.application.journey.service.JourneyNotificationOutboxStateService;
import watoo.grd.nextroute.application.journey.service.JourneyNotificationRelayService;
import watoo.grd.nextroute.application.push.service.PushTokenDeactivationService;
import watoo.grd.nextroute.common.config.JpaConfig;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.push.repository.UserPushTokenRepository;
import watoo.grd.nextroute.infrastructure.adapter.out.notification.FcmMessageFactory;
import watoo.grd.nextroute.infrastructure.adapter.out.notification.FcmNotificationSender;
import watoo.grd.nextroute.testsupport.AbstractJourneyOutboxPostgresIT;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * outbox 행 하나가 FCM 까지 가는 전 구간. SDK 만 가짜다.
 *
 * <p>단위 테스트가 분류 규칙을 잠그고, 여기서는 그 판정이 <b>DB 에 실제로 반영되는지</b>를 본다 —
 * 발송 대상이 정말 최신 기기 하나인가, 죽은 token 이 정말 꺼지는가, 일시 실패가 정말 다시
 * 대기로 돌아가는가.
 *
 * <p>relay 루프 빈은 띄우지 않는다. {@code relay.enabled=true} + {@code sender=noop} 조합을
 * 부팅에서 막기 때문에, 루프 대신 {@link JourneyNotificationRelayService} 를 직접 부른다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, JourneyNotificationOutboxStateService.class,
        JourneyNotificationRelayService.class, JourneyNotificationMetrics.class,
        PushTokenDeactivationService.class, JourneyNotificationFcmRelayIT.Config.class})
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
        "classpath:db/migration/V63__journey_leg_guidance_info.sql"
},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("FCM 발송기를 끼운 relay 전 구간")
class JourneyNotificationFcmRelayIT extends AbstractJourneyOutboxPostgresIT {

    private static final Instant NOW = Instant.parse("2026-08-10T03:00:00Z");
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
        FakeFirebaseMessagingClient fakeFirebaseMessagingClient() {
            return new FakeFirebaseMessagingClient();
        }

        @Bean
        FcmMessageFactory fcmMessageFactory(JourneyNotificationProperties properties) {
            return new FcmMessageFactory(properties);
        }

        @Bean
        NotificationSender notificationSender(UserPushTokenRepository tokenRepository,
                                              FirebaseMessagingClient client,
                                              FcmMessageFactory messageFactory,
                                              PushTokenDeactivationService deactivationService,
                                              JourneyNotificationMetrics metrics,
                                              Clock clock) {
            return new FcmNotificationSender(tokenRepository, client, messageFactory,
                    deactivationService, metrics, clock);
        }
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JourneySessionRepository sessionRepository;
    @Autowired JourneyNotificationRelayService relayService;
    @Autowired UserPushTokenRepository tokenRepository;
    @Autowired FakeFirebaseMessagingClient client;
    @Autowired JourneyNotificationProperties properties;

    private long userId;
    private long journeyId;

    @BeforeEach
    void setUp() {
        client.reset();
        jdbcTemplate.update("delete from journey_notification");
        jdbcTemplate.update("delete from user_push_token");
        jdbcTemplate.update("delete from journey_session");
        jdbcTemplate.update("delete from users");
        userId = jdbcTemplate.queryForObject(
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
    @DisplayName("가장 최근에 등록한 기기 한 대에만 보내고 행은 SENT 로 끝난다")
    void sendsToMostRecentTokenOnly() {
        insertToken("token-old", NOW.minusSeconds(7200));
        insertToken("token-new", NOW.minusSeconds(60));
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(600));

        relayService.drainUntilEmpty();

        assertThat(client.lastToken())
                .as("사용자가 지금 들고 있을 가능성이 가장 높은 기기다")
                .isEqualTo("token-new");
        assertThat(client.callCount()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("죽은 token 은 DB 에서 실제로 꺼지고 다음 발송은 그다음 기기로 간다")
    void deadTokenIsDisabledAndNextSendFallsBack() {
        long older = insertToken("token-old", NOW.minusSeconds(7200));
        long newest = insertToken("token-dead", NOW.minusSeconds(60));
        client.fail("token-dead", "UNREGISTERED");
        long first = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(600));

        relayService.drainUntilEmpty();

        assertThat(statusOf(first)).as("사용자는 아무것도 못 받았다").isEqualTo("FAILED");
        assertThat(reasonOf(first)).isEqualTo("fcm: UNREGISTERED");
        assertThat(enabledOf(newest)).isFalse();
        assertThat(enabledOf(older)).isTrue();

        // 그다음 알림은 살아 있는 기기로 간다 — 죽은 행이 대상에서 빠졌기 때문이다.
        long second = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(600));
        relayService.drainUntilEmpty();

        assertThat(client.lastToken()).isEqualTo("token-old");
        assertThat(statusOf(second)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("설정 오류로 보이는 실패는 token 을 끄지 않는다")
    void configurationErrorKeepsToken() {
        long tokenId = insertToken("token-a", NOW.minusSeconds(60));
        client.fail("token-a", "SENDER_ID_MISMATCH");
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(600));

        relayService.drainUntilEmpty();

        assertThat(statusOf(id)).isEqualTo("FAILED");
        assertThat(enabledOf(tokenId))
                .as("서버 자격증명 문제로 주소록을 지우면 안 된다")
                .isTrue();
    }

    @Test
    @DisplayName("일시 실패는 대기로 돌아가고 시각이 밀린다")
    void transientFailureReturnsToPending() {
        long tokenId = insertToken("token-a", NOW.minusSeconds(60));
        client.fail("token-a", "UNAVAILABLE");
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(1800));

        relayService.drainUntilEmpty();

        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(scheduledAtOf(id)).isEqualTo(NOW.plusSeconds(properties.getBackoffSeconds()));
        assertThat(attemptCountOf(id)).isEqualTo(1);
        assertThat(enabledOf(tokenId)).as("일시 실패로 token 을 끄지 않는다").isTrue();
    }

    @Test
    @DisplayName("보낼 곳이 없으면 재시도 없이 FAILED 다")
    void noActiveTokenIsFinal() {
        long disabled = insertToken("token-off", NOW.minusSeconds(60));
        jdbcTemplate.update("update user_push_token set enabled = false where id = ?", disabled);
        long id = enqueue(NOW.minusSeconds(1), NOW.plusSeconds(600));

        relayService.drainUntilEmpty();

        assertThat(client.callCount()).isZero();
        assertThat(statusOf(id)).isEqualTo("FAILED");
        assertThat(reasonOf(id)).isEqualTo("no-active-token");
        assertThat(tokenRepository.findByUserIdAndEnabledTrueAndDeletedAtIsNull(userId)).isEmpty();
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private long insertToken(String token, Instant lastSeenAt) {
        return jdbcTemplate.queryForObject("""
                insert into user_push_token
                    (created_at, updated_at, user_id, platform, provider, token, environment,
                     enabled, last_seen_at)
                values (now(), now(), ?, 'ANDROID', 'FCM', ?, 'PRODUCTION', true, ?)
                returning id
                """, Long.class, userId, token, Timestamp.from(lastSeenAt));
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

    private int attemptCountOf(long id) {
        return jdbcTemplate.queryForObject(
                "select attempt_count from journey_notification where id = ?", Integer.class, id);
    }

    private boolean enabledOf(long tokenId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select enabled from user_push_token where id = ?", Boolean.class, tokenId));
    }
}
