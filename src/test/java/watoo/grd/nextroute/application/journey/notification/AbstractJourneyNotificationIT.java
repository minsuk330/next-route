package watoo.grd.nextroute.application.journey.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
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
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.dto.JourneyEvaluationOutcome;
import watoo.grd.nextroute.application.journey.dto.NotificationContext;
import watoo.grd.nextroute.application.journey.dto.TransferAssessmentBatch;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.observability.JourneyNotificationMetrics;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationScheduleWriter;
import watoo.grd.nextroute.application.journey.service.JourneyNotificationEnqueuer;
import watoo.grd.nextroute.application.journey.service.JourneyRelayWakeListener;
import watoo.grd.nextroute.application.journey.service.JourneyRelayWaker;
import watoo.grd.nextroute.application.journey.service.TransferAssessmentWriter;
import watoo.grd.nextroute.application.journey.service.TransferRiskNotifier;
import watoo.grd.nextroute.application.journey.service.TransferTargetApproachNotifier;
import watoo.grd.nextroute.application.journey.service.TransferTargetApproachPolicy;
import watoo.grd.nextroute.common.config.JpaConfig;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;
import watoo.grd.nextroute.testsupport.AbstractJourneyOutboxPostgresIT;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 평가 쓰기 경로에서 알림이 쌓이는 과정을 실 PostgreSQL 로 도는 하네스.
 *
 * <p>운영 진입점({@link JourneyEvaluationScheduleWriter#finish})을 그대로 부른다 — 알림의 안전성은
 * "소유권 확인 뒤에 온다"는 <b>순서</b>가 만들기 때문에, 알림 쪽만 따로 부르면 그 성질이 검증되지
 * 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, TransferAssessmentWriter.class, TransferRiskNotifier.class,
        TransferTargetApproachNotifier.class, TransferTargetApproachPolicy.class,
        JourneyNotificationEnqueuer.class, JourneyEvaluationScheduleWriter.class,
        JourneyNotificationMetrics.class, JourneyRelayWaker.class, JourneyRelayWakeListener.class,
        // 피로 정책(#428) 판정. 기본 꺼짐이라 기존 시험의 동작은 같다.
        watoo.grd.nextroute.application.journey.service.RouteAlertBudget.class,
        // 문구 재료(#428 PR-2): 갈아탈 차 노선 이름. 정책이 켜졌을 때만 읽는다.
        watoo.grd.nextroute.application.journey.service.TransferTargetRouteNames.class,
        // 환승 위험 알림 지연(#264 보드 모드) 판단이 재탐색 설정을 본다. 기본값(보드 꺼짐)이라 동작은 같다.
        watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties.class,
        AbstractJourneyNotificationIT.Config.class})
@Sql(scripts = {
        // 컨테이너를 공유하므로 앞선 클래스가 만든 표를 먼저 지운다.
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
abstract class AbstractJourneyNotificationIT extends AbstractJourneyOutboxPostgresIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 평가기가 심어 두는 선점 시각. 이 값이 소유권 증서다. */
    protected static final Instant LEASE_UNTIL = Instant.parse("2026-08-06T00:01:00Z");

    @TestConfiguration
    static class Config {

        /** 고정 시계. 만료 시각 계산이 테스트마다 흔들리면 경계를 못 잡는다. */
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        JourneyNotificationProperties journeyNotificationProperties() {
            return new JourneyNotificationProperties();
        }
    }

    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected JourneySessionRepository sessionRepository;
    @Autowired protected TransferConnectionRepository transferRepository;
    @Autowired protected JourneyNotificationRepository notificationRepository;
    @Autowired protected JourneyEvaluationScheduleWriter scheduleWriter;
    @Autowired protected JourneyNotificationProperties properties;
    @Autowired protected Clock clock;
    @Autowired protected JourneyRelayWaker relayWaker;

    protected long userId;
    protected JourneySession journey;

    @BeforeEach
    void prepareJourney() {
        properties.setEnqueueEnabled(true);
        // 설정 빈을 테스트끼리 나눠 쓴다. kill switch 를 내린 시험이 다음 시험까지 끄면 안 된다.
        properties.getApproach().setEnabled(true);
        properties.getFatigue().setEnabled(false);
        // waker 는 빈 하나를 테스트끼리 나눠 쓴다. 앞 테스트가 남긴 신호를 비운다.
        while (relayWaker.await(0)) {
            // 남은 permit 을 모두 소진한다.
        }
        jdbcTemplate.update("delete from journey_notification");
        jdbcTemplate.update("delete from transfer_connection");
        jdbcTemplate.update("delete from journey_session");
        jdbcTemplate.update("delete from users");
        userId = jdbcTemplate.queryForObject(
                "insert into users (created_at, updated_at) values (now(), now()) returning id",
                Long.class);

        journey = sessionRepository.saveAndFlush(JourneySession.builder()
                .userId(userId)
                .startedAt(Instant.now(clock))
                .guidanceLeaseUntil(Instant.now(clock).plusSeconds(180))
                .routeSnapshot(MAPPER.createObjectNode().put("pathType", 1))
                .build());
        jdbcTemplate.update("update journey_session set next_evaluation_at = ? where id = ?",
                java.sql.Timestamp.from(LEASE_UNTIL), journey.getId());

        transferRepository.saveAndFlush(TransferConnection.builder()
                .journeyId(journey.getId())
                .routeRevision(1)
                .transferIndex(0)
                .fromLegIndex(1)
                .walkLegIndex(null)
                .toLegIndex(2)
                .status(TransferStatus.UNKNOWN)
                .build());
    }

    // ── 평가 한 번 돌리기 ─────────────────────────────────────────────────────

    /**
     * 평가 한 번이 끝난 것처럼 쓰기 경로를 돈다.
     *
     * <p>선점 시각을 매번 다시 심는다 — {@code finish} 가 성공하면 그 값을 다음 시각으로 덮으므로,
     * 그대로 두면 두 번째 평가가 소유권 확인에서 걸린다(운영에서는 새 선점이 다시 심는다).
     */
    protected boolean evaluate(TransferStatus status, NotificationChannel... channels) {
        return evaluate(status, 0, channels);
    }

    protected boolean evaluate(TransferStatus status, int currentLegIndex,
                               NotificationChannel... channels) {
        return evaluate(batchOf(status), currentLegIndex, channels);
    }


    /**
     * 갈아탈 정류장에서 기다리는 중의 평가 한 번.
     *
     * <p>대기 단계에 서 있으면 여유는 {@code 다음 차 도착 - 지금} 이다 — 접근 알림의 조건을
     * 만드는 유일한 상황이라 도착 시각을 직접 준다.
     */
    protected boolean evaluateWaiting(int secondsUntilArrival, NotificationChannel... channels) {
        Instant now = Instant.now(clock);
        Instant arrival = now.plusSeconds(secondsUntilArrival);
        long slack = secondsUntilArrival;
        TransferStatus status = slack < 0 ? TransferStatus.MISSED
                : slack < 120 ? TransferStatus.AT_RISK
                : slack < 300 ? TransferStatus.CATCHABLE
                : TransferStatus.COMFORTABLE;
        TransferRiskAssessment assessment = new TransferRiskAssessment(0, 1, 2, status,
                now, now, arrival, slack, CurrentLegArrival.Quality.GOOD, now,
                CurrentLegArrival.Source.REALTIME_ARRIVAL, null);
        return evaluate(new TransferAssessmentBatch(List.of(assessment), Map.of(), false), 2, channels);
    }

    protected boolean evaluate(TransferAssessmentBatch batch, int currentLegIndex,
                               NotificationChannel... channels) {
        jdbcTemplate.update("update journey_session set next_evaluation_at = ? where id = ?",
                java.sql.Timestamp.from(LEASE_UNTIL), journey.getId());
        JourneyEvaluationOutcome outcome = new JourneyEvaluationOutcome(
                journey.getId(), 1, batch, true);
        return scheduleWriter.finish(
                new JourneyDueClaim(journey.getId(), LEASE_UNTIL, LEASE_UNTIL),
                outcome, LEASE_UNTIL.plusSeconds(30), false, 10L, LEASE_UNTIL,
                new NotificationContext(userId, currentLegIndex, List.of(channels)));
    }

    protected TransferAssessmentBatch batchOf(TransferStatus status) {
        return new TransferAssessmentBatch(List.of(assessment(status)), Map.of(), false);
    }

    /**
     * 환승 0번의 판정 하나.
     *
     * <p>{@code MISSED} 는 <b>다음 차 도착 시각이 이미 과거</b>다 — 그것이 만료 바닥을 필요하게
     * 만드는 조건이라 여기서 그대로 재현한다.
     */
    protected TransferRiskAssessment assessment(TransferStatus status) {
        Instant now = Instant.now(clock);
        Instant nextBusArrivalAt = switch (status) {
            case MISSED -> now.minusSeconds(120);
            case AT_RISK -> now.plusSeconds(60);
            case CATCHABLE -> now.plusSeconds(200);
            case COMFORTABLE -> now.plusSeconds(600);
            case UNKNOWN -> null;
        };
        Long slack = switch (status) {
            case MISSED -> -120L;
            case AT_RISK -> 60L;
            case CATCHABLE -> 200L;
            case COMFORTABLE -> 600L;
            case UNKNOWN -> null;
        };
        return new TransferRiskAssessment(0, 1, 2, status,
                now, now, nextBusArrivalAt, slack,
                CurrentLegArrival.Quality.GOOD, now,
                status == TransferStatus.UNKNOWN ? null : CurrentLegArrival.Source.REALTIME_ARRIVAL, null);
    }

    // ── 조회 도우미 ──────────────────────────────────────────────────────────

    protected List<Map<String, Object>> notifications() {
        return jdbcTemplate.queryForList(
                "select * from journey_notification order by id");
    }

    protected String marker() {
        return jdbcTemplate.queryForObject(
                "select last_notified_status from transfer_connection where journey_id = ?",
                String.class, journey.getId());
    }
}
