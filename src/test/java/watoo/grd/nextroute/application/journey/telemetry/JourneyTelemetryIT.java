package watoo.grd.nextroute.application.journey.telemetry;

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
import watoo.grd.nextroute.application.journey.dto.JourneyTelemetryCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyTelemetryResult;
import watoo.grd.nextroute.application.journey.observability.JourneyTelemetryMetrics;
import watoo.grd.nextroute.application.journey.service.JourneyTelemetryService;
import watoo.grd.nextroute.common.config.JpaConfig;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.testsupport.AbstractJourneyOutboxPostgresIT;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 계측 수집이 무엇을 받고 무엇을 버리는지.
 *
 * <p>버리는 쪽이 더 중요하다. 남의 알림 id 로 "내가 눌렀다"를 심을 수 있으면 지표가 아니라
 * 구멍이고, {@code detail} 에 좌표가 새어 들어오면 받지 않기로 한 값을 저장하게 된다.
 *
 * <p>재전송은 <b>정상</b> 동작이다. 지하철에서 응답을 못 받은 앱이 같은 batch 를 다시 보내는데,
 * 그때 예외가 나면 batch 하나가 통째로 죽고 앱은 영원히 재시도한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, JourneyTelemetryService.class, JourneyTelemetryMetrics.class,
        JourneyTelemetryIT.Config.class})
@Sql(scripts = {
        "classpath:db/it/journey-reset.sql",
        "classpath:db/migration/V35__create_journey.sql",
        "classpath:db/migration/V36__add_journey_transit_resolution.sql",
        "classpath:db/migration/V37__align_journey_navigation_contract.sql",
        "classpath:db/migration/V38__add_journey_last_progress_id.sql",
        "classpath:db/migration/V39__create_user_push_token.sql",
        "classpath:db/migration/V40__create_journey_notification.sql",
        "classpath:db/migration/V41__create_journey_telemetry_event.sql"
},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("앱 계측 수집")
class JourneyTelemetryIT extends AbstractJourneyOutboxPostgresIT {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
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
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JourneySessionRepository sessionRepository;
    @Autowired JourneyTelemetryService service;

    private long userId;
    private long otherUserId;
    private long journeyId;
    private long otherJourneyId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from journey_telemetry_event");
        jdbcTemplate.update("delete from journey_notification");
        jdbcTemplate.update("delete from journey_session");
        jdbcTemplate.update("delete from users");
        userId = newUser();
        otherUserId = newUser();
        journeyId = newJourney(userId);
        otherJourneyId = newJourney(otherUserId);
    }

    // ── 저장·중복 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("batch 를 통째로 저장한다")
    void storesWholeBatch() {
        JourneyTelemetryResult result = record(
                event("t1", "SCREEN_ENTERED", null),
                event("t2", "FOREGROUND_SPAN", null),
                event("t3", "ALERT_SUPPRESSED", null));

        assertThat(result.accepted()).isEqualTo(3);
        assertThat(rows()).hasSize(3);
    }

    @Test
    @DisplayName("같은 열쇠로 다시 보내면 한 번만 센다 — 예외가 아니다")
    void resendIsDeduplicatedNotFailed() {
        record(event("t1", "SCREEN_ENTERED", null));

        JourneyTelemetryResult result = record(event("t1", "SCREEN_ENTERED", null));

        assertThat(result.duplicated())
                .as("지하철에서 응답을 못 받은 앱이 다시 보내는 것은 정상 동작이다")
                .isEqualTo(1);
        assertThat(rows()).hasSize(1);
    }

    @Test
    @DisplayName("한 batch 안에 같은 열쇠가 둘이어도 한 행이다")
    void duplicateWithinBatchStoresOnce() {
        JourneyTelemetryResult result = record(
                event("t1", "SCREEN_ENTERED", null),
                event("t1", "SCREEN_ENTERED", null));

        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.duplicated()).isEqualTo(1);
        assertThat(rows()).hasSize(1);
    }

    @Test
    @DisplayName("다른 사용자가 같은 열쇠를 써도 둘 다 저장된다")
    void telemetryIdIsUniquePerUser() {
        record(event("shared-key", "SCREEN_ENTERED", null));

        JourneyTelemetryResult result = service.record(otherUserId, otherJourneyId,
                List.of(command("shared-key", "SCREEN_ENTERED", null, null)));

        assertThat(result.accepted())
                .as("전역 유일이면 기기를 물려받아 계정이 바뀔 때 남의 키가 내 이벤트를 삼킨다")
                .isEqualTo(1);
        assertThat(rows()).hasSize(2);
    }

    @Test
    @DisplayName("한 이벤트가 길이 제한을 넘어도 그 행만 버리고 batch 는 산다")
    void oversizedFieldRejectsOnlyThatRow() {
        String tooLongTelemetryId = "x".repeat(65);
        String tooLongEventType = "y".repeat(41);

        JourneyTelemetryResult result = record(
                event("t1", "SCREEN_ENTERED", null),
                event(tooLongTelemetryId, "SCREEN_ENTERED", null),
                event("t2", tooLongEventType, null));

        assertThat(result.accepted())
                .as("한 필드가 varchar 제한을 넘겨 DB 에 그대로 넣으면 그 예외가 트랜잭션 전체를 "
                        + "죽인다 — Bean Validation 을 걷어낸 것과 같은 사고가 저장소 앞에서 재발한다")
                .isEqualTo(1);
        assertThat(result.rejected()).isEqualTo(2);
        assertThat(rows()).hasSize(1);
    }

    // ── 권한 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("남의 여정이면 요청 전체가 실패한다")
    void foreignJourneyFailsWholeRequest() {
        assertThatThrownBy(() -> service.record(userId, otherJourneyId,
                List.of(command("t1", "SCREEN_ENTERED", null, null))))
                .isInstanceOf(BusinessException.class);

        assertThat(rows()).isEmpty();
    }

    @Test
    @DisplayName("남의 알림 id 는 그 행만 버리고 batch 는 산다")
    void foreignAlertRejectsOnlyThatRow() {
        long foreignAlert = insertAlert(otherUserId, otherJourneyId);

        JourneyTelemetryResult result = record(
                event("t1", "ALERT_OPENED", foreignAlert),
                event("t2", "SCREEN_ENTERED", null));

        assertThat(result.rejected())
                .as("남의 알림에 '내가 눌렀다'를 붙이면 선택률이 엉뚱한 발송에 붙는다")
                .isEqualTo(1);
        assertThat(result.accepted()).isEqualTo(1);
        assertThat(rows()).hasSize(1);
    }

    @Test
    @DisplayName("알림 참조 없는 열람 이벤트는 버린다")
    void alertOpenedWithoutAlertIsRejected() {
        JourneyTelemetryResult result = record(event("t1", "ALERT_OPENED", null));

        assertThat(result.rejected())
                .as("어떤 알림에 대한 반응인지 없으면 선택률에 기여하지 못한다")
                .isEqualTo(1);
        assertThat(rows()).isEmpty();
    }

    @Test
    @DisplayName("내 알림이면 수집 시점에 알림 메타를 복사해 둔다")
    void ownAlertSnapshotsMetadata() {
        long alertId = insertAlert(userId, journeyId);

        record(event("t1", "ALERT_OPENED", alertId));

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.get("alert_id")).isEqualTo(alertId);
            assertThat(row.get("alert_checkpoint"))
                    .as("알림 행이 나중에 지워져도 무엇에 대한 반응이었는지 남아야 한다")
                    .isEqualTo("TRANSFER_RISK_AT_RISK");
            assertThat(row.get("alert_sent_at")).isNotNull();
        });
    }

    // ── detail 필터 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("허용 키만 저장한다 — 좌표는 들어오지 못한다")
    void detailKeepsAllowedKeysOnly() {
        record(event("t1", "ALERT_PRESENTED", insertAlert(userId, journeyId),
                Map.of("surface", "OS_NOTIFICATION", "lat", 37.5, "lng", 127.0)));

        assertThat(detailOf("t1"))
                .containsEntry("surface", "OS_NOTIFICATION")
                .doesNotContainKeys("lat", "lng");
    }

    @Test
    @DisplayName("중첩 객체는 통째로 버린다 — 키 이름만 막으면 좌표가 숨어 들어온다")
    void detailDropsNestedValues() {
        record(event("t1", "SCREEN_ENTERED",
                null, Map.of("origin", Map.of("lat", 37.5))));

        assertThat(detailOf("t1")).isNull();
    }

    @Test
    @DisplayName("모르는 이벤트도 저장하되 부가 값은 남기지 않는다")
    void unknownEventTypeIsStoredWithoutDetail() {
        JourneyTelemetryResult result = record(event("t1", "SOMETHING_NEW",
                null, Map.of("whatever", "value")));

        assertThat(result.accepted())
                .as("앱이 먼저 배포되는 것이 정상 순서다. 거부하면 새 이벤트가 통째로 버려진다")
                .isEqualTo(1);
        assertThat(detailOf("t1")).isNull();
    }

    @Test
    @DisplayName("보관 기준 시각은 서버가 찍는다")
    void receivedAtIsServerTime() {
        record(event("t1", "SCREEN_ENTERED", null));

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(((Timestamp) row.get("received_at")).toInstant())
                    .as("기기 시각으로 지우면 시계를 미래로 돌린 기기의 데이터가 영원히 남는다")
                    .isEqualTo(NOW);
        });
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private JourneyTelemetryResult record(JourneyTelemetryCommand... commands) {
        return service.record(userId, journeyId, List.of(commands));
    }

    private JourneyTelemetryCommand event(String telemetryId, String eventType, Long alertId) {
        return command(telemetryId, eventType, alertId, null);
    }

    private JourneyTelemetryCommand event(String telemetryId, String eventType, Long alertId,
                                          Map<String, Object> detail) {
        return command(telemetryId, eventType, alertId, detail);
    }

    private JourneyTelemetryCommand command(String telemetryId, String eventType, Long alertId,
                                            Map<String, Object> detail) {
        return new JourneyTelemetryCommand(telemetryId, eventType, NOW.minusSeconds(30), 1,
                alertId, detail == null ? null : MAPPER.valueToTree(detail));
    }

    private long insertAlert(long ownerId, long ownerJourneyId) {
        String key = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject("""
                insert into journey_notification
                    (created_at, updated_at, journey_id, user_id, route_revision,
                     command_version, evaluation_version, scope_type, scope_id, checkpoint,
                     channel, logical_checkpoint_key, delivery_key, status,
                     scheduled_at, expires_at, sent_at, attempt_count, requeue_count)
                values (now(), now(), ?, ?, 1, 1, 1, 'TRANSFER', 0, 'TRANSFER_RISK_AT_RISK',
                        'PUSH', ?, ? || ':PUSH', 'SENT', now(), now() + interval '10 minutes',
                        now(), 1, 0)
                returning id
                """, Long.class, ownerJourneyId, ownerId, key, key);
    }

    private long newUser() {
        return jdbcTemplate.queryForObject(
                "insert into users (created_at, updated_at) values (now(), now()) returning id",
                Long.class);
    }

    private long newJourney(long ownerId) {
        return sessionRepository.saveAndFlush(JourneySession.builder()
                .userId(ownerId)
                .startedAt(NOW)
                .guidanceLeaseUntil(NOW.plusSeconds(180))
                .routeSnapshot(MAPPER.createObjectNode().put("pathType", 1))
                .build()).getId();
    }

    private List<Map<String, Object>> rows() {
        return jdbcTemplate.queryForList("select * from journey_telemetry_event order by id");
    }

    private Map<String, Object> detailOf(String telemetryId) {
        String json = jdbcTemplate.queryForObject(
                "select detail from journey_telemetry_event where telemetry_id = ?",
                String.class, telemetryId);
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
