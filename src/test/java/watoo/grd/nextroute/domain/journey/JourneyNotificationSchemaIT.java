package watoo.grd.nextroute.domain.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import watoo.grd.nextroute.common.config.JpaConfig;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotification;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationScope;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationStatus;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;
import watoo.grd.nextroute.testsupport.AbstractJourneyOutboxPostgresIT;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V40 이 만든 표와 {@link JourneyNotification} 매핑, 그리고
 * {@link JourneyNotificationRepository} 의 SQL 을 실 PostgreSQL 로 확인한다.
 *
 * <p>스키마를 복제하지 않고 <b>실제 마이그레이션 파일을 그대로 실행</b>한다({@code @Sql}).
 *
 * <p>여기서 고정하는 것 중 가장 중요한 것은 {@code delivery_key} 의 unique 가 <b>부분 인덱스가
 * 아니라는 것</b>이다. soft delete 조건을 붙이면 행을 지우는 순간 키가 열려 같은 알림이 다시
 * 나간다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaConfig.class)
@Sql(scripts = {
        // 컨테이너를 공유하므로 앞선 클래스가 만든 표를 먼저 지운다.
        "classpath:db/it/journey-reset.sql",
        "classpath:db/migration/V35__create_journey.sql",
        "classpath:db/migration/V36__add_journey_transit_resolution.sql",
        "classpath:db/migration/V37__align_journey_navigation_contract.sql",
        "classpath:db/migration/V38__add_journey_last_progress_id.sql",
        "classpath:db/migration/V39__create_user_push_token.sql",
        "classpath:db/migration/V40__create_journey_notification.sql",
        "classpath:db/migration/V41__create_journey_telemetry_event.sql",
        "classpath:db/migration/V53__add_reroute_alert_state.sql",
        "classpath:db/migration/V59__journey_session_kind.sql",
        "classpath:db/migration/V63__journey_leg_guidance_info.sql",
        "classpath:db/migration/V65__journey_leg_alight_door_side.sql",
        "classpath:db/migration/V66__journey_session_evaluation_urgent.sql",
        "classpath:db/migration/V67__journey_reevaluation_trigger.sql"
},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class JourneyNotificationSchemaIT extends AbstractJourneyOutboxPostgresIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JourneySessionRepository sessionRepository;
    @Autowired JourneyNotificationRepository notificationRepository;

    private long userId;
    private JourneySession journey;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from journey_notification");
        jdbcTemplate.update("delete from journey_session");
        jdbcTemplate.update("delete from users");
        userId = jdbcTemplate.queryForObject(
                "insert into users (created_at, updated_at) values (now(), now()) returning id",
                Long.class);
        journey = sessionRepository.saveAndFlush(JourneySession.builder()
                .userId(userId)
                .startedAt(Instant.now())
                .guidanceLeaseUntil(Instant.now().plusSeconds(180))
                .routeSnapshot(json("{\"pathType\":1}"))
                .build());
    }

    private JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /** 운영 경로와 같은 방식으로 넣는다 — enqueue SQL 은 {@code @Transactional} 안에서만 돈다. */
    private List<Long> enqueue(JourneyCheckpoint checkpoint, NotificationChannel channel,
                               int scopeId, Instant now, Instant expiresAt) {
        String logicalKey = journey.getId() + ":1:TRANSFER:" + scopeId + ":" + checkpoint.name();
        return tx().execute(status -> notificationRepository.enqueue(
                journey.getId(), 1, "TRANSFER", scopeId, checkpoint.name(), channel.name(),
                logicalKey, logicalKey + ":" + channel.name(), now, expiresAt,
                "{\"transferIndex\":" + scopeId + "}", LocalDateTime.now()));
    }

    private List<Long> enqueue(JourneyCheckpoint checkpoint, NotificationChannel channel) {
        Instant now = Instant.now();
        return enqueue(checkpoint, channel, 0, now, now.plusSeconds(600));
    }

    private String status(long id) {
        return jdbcTemplate.queryForObject(
                "select status from journey_notification where id = ?", String.class, id);
    }

    // ── 스키마 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("발송 키 unique 는 부분 인덱스가 아니다 — soft delete 해도 같은 키를 다시 못 넣는다")
    void deliveryKeyUnique_isNotPartial() {
        Long id = enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH).get(0);

        assertThat(indexDefinition("uq_journey_notification_delivery"))
                .as("삭제 순간 키가 열리면 같은 알림이 다시 나간다")
                .contains("UNIQUE")
                .doesNotContain("WHERE");

        jdbcTemplate.update("update journey_notification set deleted_at = now() where id = ?", id);

        assertThat(enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH))
                .as("soft delete 된 행이 있어도 ON CONFLICT 가 삼킨다")
                .isEmpty();
    }

    @Test
    @DisplayName("채널이 둘이면 행은 둘, 논리 키는 하나다")
    void twoChannels_twoRows_oneLogicalKey() {
        enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH);
        enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.TOSS);

        assertThat(notificationRepository.findAll()).hasSize(2);
        assertThat(jdbcTemplate.queryForList(
                "select distinct logical_checkpoint_key from journey_notification", String.class))
                .hasSize(1);
    }

    @Test
    @DisplayName("같은 채널로 두 번 넣으면 두 번째는 조용히 사라진다")
    void sameDeliveryKey_secondInsertIsNoOp() {
        assertThat(enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH))
                .hasSize(1);
        assertThat(enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH))
                .isEmpty();
    }

    @Test
    @DisplayName("상태 승격은 발송 키가 달라 삼켜지지 않는다")
    void statusPromotion_hasDistinctDeliveryKey() {
        enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH);
        enqueue(JourneyCheckpoint.TRANSFER_RISK_MISSED, NotificationChannel.PUSH);

        assertThat(jdbcTemplate.queryForList(
                "select delivery_key from journey_notification order by id", String.class))
                .as("체크포인트가 상태를 품지 않으면 MISSED 가 ON CONFLICT 에 삼켜진다")
                .hasSize(2)
                .satisfies(keys -> assertThat(keys.get(0)).isNotEqualTo(keys.get(1)));
    }

    @Test
    @DisplayName("값 목록을 지키는 CHECK 가 걸려 있다")
    void checkConstraints_reject() {
        assertThatThrownBy(() -> rawInsert("BUS_STOP", 0, "PUSH", 1L, 0L))
                .as("scope_type 값 목록")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rawInsert("TRANSFER", 0, "FCM", 1L, 0L))
                .as("FCM 은 채널이 아니라 provider 다")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rawInsert("TRANSFER", 0, "PUSH", 0L, 0L))
                .as("command_version >= 1")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rawInsert("TRANSFER", 0, "PUSH", 1L, -1L))
                .as("evaluation_version >= 0")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rawInsert("TRANSFER", -1, "PUSH", 1L, 0L))
                .as("scope_id >= 0")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("체크포인트에는 CHECK 가 없다 — 어휘는 Java enum 이 소유한다")
    void checkpoint_hasNoCheckConstraint() {
        List<String> checks = jdbcTemplate.queryForList("""
                select conname from pg_constraint
                where contype = 'c' and conrelid = 'journey_notification'::regclass
                """, String.class);

        assertThat(checks)
                .as("값 목록을 늘리는 PR4 에는 마이그레이션이 없다")
                .noneMatch(name -> name.contains("checkpoint"));
        assertThat(checks).contains(
                "journey_notification_scope_type_check",
                "journey_notification_channel_check",
                "journey_notification_status_check",
                "journey_notification_command_version_check",
                "journey_notification_evaluation_version_check");
    }

    @Test
    @DisplayName("claim·회수·취소 경로가 쓸 인덱스가 실제로 걸려 있다")
    void indexes_exist_withPredicates() {
        assertThat(indexDefinition("uq_journey_notification_delivery")).contains("UNIQUE");
        assertThat(indexDefinition("idx_journey_notification_logical"))
                .doesNotContain("UNIQUE");
        assertThat(indexDefinition("idx_journey_notification_claim"))
                .contains("scheduled_at").contains("PENDING");
        assertThat(indexDefinition("idx_journey_notification_processing"))
                .contains("processing_started_at").contains("PROCESSING");
        assertThat(indexDefinition("idx_journey_notification_journey_live"))
                .contains("journey_id").contains("PENDING").contains("PROCESSING");
        assertThat(indexDefinition("idx_journey_notification_dead")).contains("DEAD");
    }

    private String indexDefinition(String indexName) {
        return jdbcTemplate.queryForObject(
                "select indexdef from pg_indexes where indexname = ?", String.class, indexName);
    }

    private void rawInsert(String scopeType, int scopeId, String channel,
                           long commandVersion, long evaluationVersion) {
        jdbcTemplate.update("""
                insert into journey_notification
                    (created_at, updated_at, journey_id, user_id, route_revision,
                     command_version, evaluation_version, scope_type, scope_id, checkpoint,
                     channel, logical_checkpoint_key, delivery_key, status,
                     scheduled_at, expires_at, attempt_count, requeue_count)
                values (now(), now(), ?, ?, 1, ?, ?, ?, ?, 'TRANSFER_RISK_AT_RISK', ?,
                        'k', 'k:' || ?, 'PENDING', now(), now() + interval '10 minutes', 0, 0)
                """, journey.getId(), userId, commandVersion, evaluationVersion,
                scopeType, scopeId, channel, channel);
    }

    // ── 매핑 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("절대시각과 jsonb 가 값을 잃지 않고 오간다")
    void instantAndJsonb_roundTrip() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Long id = enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH,
                0, now, now.plusSeconds(600)).get(0);

        assertThat(jdbcTemplate.queryForObject(
                "select jsonb_typeof(payload) from journey_notification where id = ?",
                String.class, id))
                .as("문자열이 아니라 객체로 들어가야 PR6 이 payload 를 질의할 수 있다")
                .isEqualTo("object");

        JourneyNotification loaded = notificationRepository.findById(id).orElseThrow();
        assertThat(loaded.getScheduledAt()).isEqualTo(now);
        assertThat(loaded.getExpiresAt()).isEqualTo(now.plusSeconds(600));
        assertThat(loaded.getPayload().get("transferIndex").asInt()).isZero();
        assertThat(loaded.getStatus()).isEqualTo(JourneyNotificationStatus.PENDING);
        assertThat(loaded.getCheckpoint()).isEqualTo(JourneyCheckpoint.TRANSFER_RISK_AT_RISK);
        assertThat(loaded.getChannel()).isEqualTo(NotificationChannel.PUSH);
        assertThat(loaded.getScopeType()).isEqualTo(JourneyNotificationScope.TRANSFER);
        assertThat(loaded.getUserId()).isEqualTo(userId);
        assertThat(loaded.getAttemptCount()).isZero();
    }

    @Test
    @DisplayName("두 version 은 여정 행에서 그대로 집어 온다")
    void versions_copiedFromJourney() {
        jdbcTemplate.update(
                "update journey_session set command_version = 7, evaluation_version = 12 where id = ?",
                journey.getId());

        Long id = enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH).get(0);

        JourneyNotification loaded = notificationRepository.findById(id).orElseThrow();
        assertThat(loaded.getCommandVersion()).isEqualTo(7L);
        assertThat(loaded.getEvaluationVersion()).isEqualTo(12L);
    }

    // ── enqueue CTE ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("경로 개정본이 다르면 아무것도 쌓지 않는다")
    void routeRevisionMismatch_insertsNothing() {
        List<Long> inserted = tx().execute(status -> notificationRepository.enqueue(
                journey.getId(), 2, "TRANSFER", 0, "TRANSFER_RISK_AT_RISK", "PUSH",
                "k", "k:PUSH", Instant.now(), Instant.now().plusSeconds(600), null,
                LocalDateTime.now()));

        assertThat(inserted).isEmpty();
    }

    @Test
    @DisplayName("여정이 진행 중이 아니면 아무것도 쌓지 않는다")
    void endedJourney_insertsNothing() {
        jdbcTemplate.update("update journey_session set status = 'COMPLETED' where id = ?",
                journey.getId());

        assertThat(enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH))
                .isEmpty();
    }

    @Test
    @DisplayName("payload 가 null 이어도 행은 쌓인다")
    void nullPayload_isAccepted() {
        List<Long> inserted = tx().execute(status -> notificationRepository.enqueue(
                journey.getId(), 1, "TRANSFER", 0, "TRANSFER_RISK_AT_RISK", "PUSH",
                "k", "k:PUSH", Instant.now(), Instant.now().plusSeconds(600), null,
                LocalDateTime.now()));

        assertThat(inserted).hasSize(1);
        assertThat(notificationRepository.findById(inserted.get(0)).orElseThrow().getPayload())
                .isNull();
    }

    // ── claim / reclaim ──────────────────────────────────────────────────────

    @Test
    @DisplayName("claim 은 PROCESSING 으로 바꾸고 시도 횟수를 올린다")
    void claim_marksProcessing_andIncrementsAttempt() {
        Long id = enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH).get(0);

        List<Long> claimed = tx().execute(s -> notificationRepository
                .claimBatchForProcessing(Instant.now(), 10, LocalDateTime.now()));

        assertThat(claimed).containsExactly(id);
        JourneyNotification loaded = notificationRepository.findById(id).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(JourneyNotificationStatus.PROCESSING);
        assertThat(loaded.getAttemptCount()).isEqualTo(1);
        assertThat(loaded.getProcessingStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("아직 시각이 안 된 행은 claim 하지 않는다")
    void claim_skipsFutureScheduled() {
        Instant now = Instant.now();
        enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH,
                0, now.plusSeconds(60), now.plusSeconds(600));

        List<Long> claimed = tx().execute(s -> notificationRepository
                .claimBatchForProcessing(now, 10, LocalDateTime.now()));

        assertThat(claimed).isEmpty();
    }

    @Test
    @DisplayName("고착 PROCESSING 은 회수돼 다시 대기로 간다")
    void reclaimStale_returnsToPending() {
        Instant claimedAt = Instant.now().minusSeconds(600);
        Long id = enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH,
                0, claimedAt, claimedAt.plusSeconds(1800)).get(0);
        tx().execute(s -> notificationRepository
                .claimBatchForProcessing(claimedAt, 10, LocalDateTime.now()));

        Instant now = Instant.now();
        int reclaimed = tx().execute(s -> notificationRepository
                .reclaimStale(now.minusSeconds(180), now));

        assertThat(reclaimed).isEqualTo(1);
        assertThat(status(id)).isEqualTo("PENDING");
    }

    // ── supersede / cancel ───────────────────────────────────────────────────

    @Test
    @DisplayName("새 알림이 같은 대상의 낡은 대기 알림을 밀어낸다")
    void supersede_cancelsOlderPending_exceptTheNewOnes() {
        Long old = enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH).get(0);
        Long fresh = enqueue(JourneyCheckpoint.TRANSFER_RISK_MISSED, NotificationChannel.PUSH).get(0);
        String freshKey = jdbcTemplate.queryForObject(
                "select delivery_key from journey_notification where id = ?", String.class, fresh);

        int superseded = tx().execute(s -> notificationRepository.supersedePendingByScope(
                journey.getId(), 1, JourneyNotificationScope.TRANSFER, 0, List.of(freshKey)));

        assertThat(superseded).isEqualTo(1);
        assertThat(status(old)).isEqualTo("CANCELED");
        assertThat(status(fresh)).isEqualTo("PENDING");
    }

    /** 회복이 지울 수 있는 체크포인트. 접근 알림은 여기 없다(TransferRiskNotifier 와 같은 목록). */
    private static final java.util.Set<JourneyCheckpoint> RISK_CHECKPOINTS = java.util.Set.of(
            JourneyCheckpoint.TRANSFER_RISK_AT_RISK, JourneyCheckpoint.TRANSFER_RISK_MISSED);

    @Test
    @DisplayName("회복 경로는 그 대상의 위험 알림만 밀어낸다 — 발송 중인 것과 접근 알림은 빼고")
    void supersedeAll_cancelsPending_butNotProcessing() {
        Long pending = enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH).get(0);
        Long processing = enqueue(JourneyCheckpoint.TRANSFER_RISK_MISSED, NotificationChannel.PUSH).get(0);
        jdbcTemplate.update(
                "update journey_notification set status = 'PROCESSING' where id = ?", processing);

        int superseded = tx().execute(s -> notificationRepository.supersedeAllPendingByScope(
                journey.getId(), 1, JourneyNotificationScope.TRANSFER, 0, RISK_CHECKPOINTS));

        assertThat(superseded).isEqualTo(1);
        assertThat(status(pending)).isEqualTo("CANCELED");
        assertThat(status(processing))
                .as("발송 중인 행은 relay 의 발송 직전 재확인이 맡는다")
                .isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("다른 환승의 알림은 밀려나지 않는다")
    void supersede_doesNotTouchOtherScope() {
        Instant now = Instant.now();
        Long other = enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH,
                1, now, now.plusSeconds(600)).get(0);
        enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH,
                0, now, now.plusSeconds(600));

        tx().execute(s -> notificationRepository.supersedeAllPendingByScope(
                journey.getId(), 1, JourneyNotificationScope.TRANSFER, 0, RISK_CHECKPOINTS));

        assertThat(status(other)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("여정이 끝나면 대기 알림만 취소된다")
    void cancelPendingByJourneyId_onlyPending() {
        Long pending = enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH).get(0);
        Long sent = enqueue(JourneyCheckpoint.TRANSFER_RISK_MISSED, NotificationChannel.PUSH).get(0);
        jdbcTemplate.update("update journey_notification set status = 'SENT' where id = ?", sent);

        int canceled = tx().execute(s ->
                notificationRepository.cancelPendingByJourneyId(journey.getId()));

        assertThat(canceled).isEqualTo(1);
        assertThat(status(pending)).isEqualTo("CANCELED");
        assertThat(status(sent)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("만료 배치는 여러 여정의 대기 알림을 한 번에 취소한다")
    void cancelPendingByJourneyIdIn() {
        Long first = enqueue(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH).get(0);

        int canceled = tx().execute(s -> notificationRepository
                .cancelPendingByJourneyIdIn(List.of(journey.getId(), journey.getId() + 999)));

        assertThat(canceled).isEqualTo(1);
        assertThat(status(first)).isEqualTo("CANCELED");
    }
}
