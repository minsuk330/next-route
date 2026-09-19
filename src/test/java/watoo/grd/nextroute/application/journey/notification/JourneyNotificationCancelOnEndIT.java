package watoo.grd.nextroute.application.journey.notification;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.JourneyLeaseExpiryService;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * 여정이 끝나면 아직 안 나간 알림도 함께 접힌다.
 *
 * <p>여정이 끝나는 길은 다섯이지만 {@code JourneySession.end()} 를 부르는 자리는 셋이다 —
 * 명령 경로(취소·완료·앱이 보고한 lease 만료), 새 여정으로 대체, 서버 lease 만료 배치. 셋 다
 * 확인한다.
 *
 * <p>만료 경로는 반드시 {@code sweep()} 을 부른다. {@code expireBatch()} 를 직접 부르면 프록시를
 * 타서 트랜잭션이 걸리는데, 운영 진입점인 {@code sweep()} 은 같은 클래스 안에서 부르므로 걸리지
 * 않는다 — 그 차이가 이 테스트의 존재 이유다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("여정이 끝나면 대기 중인 알림도 취소된다")
class JourneyNotificationCancelOnEndIT {

    private static final String SESSION_ID = "sess-journey-notification-cancel-it";
    private static final String PATH_KEY = "0";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired ApplyJourneyEventUseCase applyJourneyEventUseCase;
    @Autowired JourneyLeaseExpiryService expiryService;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean RouteSearchSnapshotPort snapshotPort;
    /** 취소 쿼리를 일부러 실패시켜 만료와 함께 롤백되는지 본다. */
    @SpyBean JourneyNotificationRepository notificationRepository;

    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, busPath(), Instant.now().plusSeconds(600))));
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    @Test
    @DisplayName("사용자가 접으면 대기 알림은 취소되고 발송 중·발송 완료는 그대로다")
    void userCancelCancelsPendingOnly() {
        Long ownerId = newUser();
        JourneySnapshot journey = start(ownerId);
        Long pending = enqueue(journey.journeyId(), "PENDING", "k1");
        Long processing = enqueue(journey.journeyId(), "PROCESSING", "k2");
        Long sent = enqueue(journey.journeyId(), "SENT", "k3");

        applyJourneyEventUseCase.apply(ownerId, journey.journeyId(), new JourneyCommand(
                UUID.randomUUID(), JourneyEventType.JOURNEY_CANCELED,
                journey.commandVersion(), journey.routeRevision(), null, null, Instant.now()));

        assertThat(statusOf(pending)).isEqualTo("CANCELED");
        assertThat(reasonOf(pending)).isEqualTo("journey-ended");
        assertThat(statusOf(processing))
                .as("발송 중인 행은 relay 의 발송 직전 재확인이 맡는다")
                .isEqualTo("PROCESSING");
        assertThat(statusOf(sent)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("취소 뒤에도 여정의 명령 번호와 종료 상태가 정상 기록된다")
    void journeyStateSurvivesCancelQuery() {
        Long ownerId = newUser();
        JourneySnapshot journey = start(ownerId);
        enqueue(journey.journeyId(), "PENDING", "k1");

        applyJourneyEventUseCase.apply(ownerId, journey.journeyId(), new JourneyCommand(
                UUID.randomUUID(), JourneyEventType.JOURNEY_COMPLETED,
                journey.commandVersion(), journey.routeRevision(), null, null, Instant.now()));

        assertThat(journeyStatusOf(journey.journeyId())).isEqualTo("COMPLETED");
        assertThat(commandVersionOf(journey.journeyId()))
                .as("취소 쿼리가 영속성 컨텍스트를 비우면 이 증가가 조용히 사라진다")
                .isEqualTo(journey.commandVersion() + 1);
    }

    @Test
    @DisplayName("새 여정으로 갈아타면 옛 여정의 대기 알림이 취소된다")
    void replaceCancelsPreviousJourneyNotifications() {
        Long ownerId = newUser();
        JourneySnapshot first = start(ownerId);
        Long pending = enqueue(first.journeyId(), "PENDING", "k1");

        startJourneyUseCase.start(ownerId, new StartJourneyCommand(SESSION_ID, PATH_KEY, true));

        assertThat(statusOf(pending)).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("서버 만료 배치(sweep)도 대기 알림을 취소한다")
    void sweepCancelsNotifications() {
        Long ownerId = newUser();
        JourneySnapshot journey = start(ownerId);
        Long pending = enqueue(journey.journeyId(), "PENDING", "k1");
        setLeaseAge(journey.journeyId(), "3 minutes");

        expiryService.sweep();

        assertThat(journeyStatusOf(journey.journeyId())).isEqualTo("EXPIRED");
        assertThat(statusOf(pending)).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("취소가 실패하면 만료도 함께 롤백된다")
    void sweepRollsBackWhenCancelFails() {
        Long ownerId = newUser();
        JourneySnapshot journey = start(ownerId);
        Long pending = enqueue(journey.journeyId(), "PENDING", "k1");
        setLeaseAge(journey.journeyId(), "3 minutes");
        willThrow(new IllegalStateException("취소 실패"))
                .given(notificationRepository).cancelPendingByJourneyIdIn(any());

        assertThatThrownBy(() -> expiryService.sweep()).isInstanceOf(IllegalStateException.class);

        assertThat(journeyStatusOf(journey.journeyId()))
                .as("따로 커밋되면 '만료됐는데 알림은 살아 있는' 상태가 생긴다")
                .isEqualTo("ACTIVE");
        assertThat(statusOf(pending)).isEqualTo("PENDING");
    }

    // ── 재탐색 제안도 같이 접힌다 ───────────────────────────────────────────

    @Test
    @DisplayName("사용자가 접으면 살아 있는 재탐색 제안도 함께 접힌다")
    void userCancelResolvesRerouteProposal() {
        Long ownerId = newUser();
        JourneySnapshot journey = start(ownerId);
        Long proposalId = insertActiveProposal(journey.journeyId());

        applyJourneyEventUseCase.apply(ownerId, journey.journeyId(), new JourneyCommand(
                UUID.randomUUID(), JourneyEventType.JOURNEY_CANCELED,
                journey.commandVersion(), journey.routeRevision(), null, null, Instant.now()));

        assertThat(proposalStatusOf(proposalId))
                .as("남겨 두면 수락 API 가 '여정은 끝났는데 제안은 유효한' 상태를 본다")
                .isEqualTo("SUPERSEDED");
        assertThat(proposalReasonOf(proposalId)).isEqualTo("journey-ended");
    }

    @Test
    @DisplayName("서버 만료 배치(sweep)도 재탐색 제안을 접는다")
    void sweepResolvesRerouteProposal() {
        Long ownerId = newUser();
        JourneySnapshot journey = start(ownerId);
        Long proposalId = insertActiveProposal(journey.journeyId());
        setLeaseAge(journey.journeyId(), "3 minutes");

        expiryService.sweep();

        assertThat(journeyStatusOf(journey.journeyId())).isEqualTo("EXPIRED");
        assertThat(proposalStatusOf(proposalId)).isEqualTo("SUPERSEDED");
        assertThat(proposalReasonOf(proposalId)).isEqualTo("journey-ended");
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private JourneySnapshot start(Long ownerId) {
        return startJourneyUseCase.start(ownerId, new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
    }

    /** outbox 행 하나. 평가기를 돌리지 않고 상태만 만든다 — 여기서 볼 것은 취소 갈고리뿐이다. */
    private Long enqueue(Long journeyId, String status, String keySuffix) {
        return jdbcTemplate.queryForObject("""
                insert into journey_notification
                    (created_at, updated_at, journey_id, user_id, route_revision,
                     command_version, evaluation_version, scope_type, scope_id, checkpoint,
                     channel, logical_checkpoint_key, delivery_key, status,
                     scheduled_at, expires_at, attempt_count, requeue_count)
                select now(), now(), s.id, s.user_id, s.route_revision, s.command_version,
                       s.evaluation_version, 'TRANSFER', 0, 'TRANSFER_RISK_AT_RISK', 'PUSH',
                       ?, ?, ?, now(), now() + interval '10 minutes', 0, 0
                from journey_session s where s.id = ?
                returning id
                """, Long.class,
                journeyId + ":" + keySuffix, journeyId + ":" + keySuffix + ":PUSH",
                status, journeyId);
    }

    private void setLeaseAge(Long journeyId, String interval) {
        jdbcTemplate.update("""
                update journey_session
                set guidance_lease_until = now() - interval '%s',
                    last_heartbeat_at = now() - interval '%s'
                where id = ?
                """.formatted(interval, interval), journeyId);
    }

    private String statusOf(Long notificationId) {
        return jdbcTemplate.queryForObject(
                "select status from journey_notification where id = ?", String.class, notificationId);
    }

    private String reasonOf(Long notificationId) {
        return jdbcTemplate.queryForObject(
                "select last_failure_reason from journey_notification where id = ?",
                String.class, notificationId);
    }

    /** 살아 있는 제안 하나. 후보 생성기(③④)가 없으므로 행만 손으로 만든다. */
    private Long insertActiveProposal(Long journeyId) {
        return jdbcTemplate.queryForObject("""
                insert into reroute_proposal
                    (created_at, updated_at, journey_id, user_id, route_revision,
                     kind, status, base_command_version, candidates,
                     snapshot_observed_at, expires_at)
                select now(), now(), s.id, s.user_id, s.route_revision,
                       'ROUTE_OPTION', 'ACTIVE', s.command_version,
                       '{"version":1,"candidates":[]}'::jsonb, now(), now() + interval '2 minutes'
                from journey_session s where s.id = ?
                returning id
                """, Long.class, journeyId);
    }

    private String proposalStatusOf(Long proposalId) {
        return jdbcTemplate.queryForObject(
                "select status from reroute_proposal where id = ?", String.class, proposalId);
    }

    private String proposalReasonOf(Long proposalId) {
        return jdbcTemplate.queryForObject(
                "select resolved_reason from reroute_proposal where id = ?",
                String.class, proposalId);
    }

    private String journeyStatusOf(Long journeyId) {
        return jdbcTemplate.queryForObject(
                "select status from journey_session where id = ?", String.class, journeyId);
    }

    private long commandVersionOf(Long journeyId) {
        return jdbcTemplate.queryForObject(
                "select command_version from journey_session where id = ?", Long.class, journeyId);
    }

    private Long newUser() {
        Long id = jdbcTemplate.queryForObject("""
                insert into users (created_at, updated_at, token_generation)
                values (now(), now(), 0) returning id
                """, Long.class);
        createdUserIds.add(id);
        return id;
    }

    private void deleteJourneysOf(Long id) {
        jdbcTemplate.update("""
                delete from journey_notification where journey_id in (
                    select id from journey_session where user_id = ?)
                """, id);
        jdbcTemplate.update("""
                delete from reroute_proposal where journey_id in (
                    select id from journey_session where user_id = ?)
                """, id);
        jdbcTemplate.update("""
                update journey_leg set selected_route_option_id = null
                where journey_id in (select id from journey_session where user_id = ?)
                """, id);
        jdbcTemplate.update("""
                delete from journey_leg_route_option where journey_leg_id in (
                    select l.id from journey_leg l join journey_session s on s.id = l.journey_id
                    where s.user_id = ?)
                """, id);
        for (String table : List.of("journey_leg", "journey_revision",
                "transfer_connection", "journey_event")) {
            jdbcTemplate.update("delete from " + table
                    + " where journey_id in (select id from journey_session where user_id = ?)", id);
        }
        jdbcTemplate.update("delete from journey_session where user_id = ?", id);
    }

    private PathResult busPath() {
        return new PathResult(1,
                new PathInfo(25, 2000, 500, 1, "정류장A", "정류장F", null),
                List.of(
                        SubPathFixture.bus(20).names("정류장A", "정류장F")
                                .busStops("ST-A", "ST-F")
                                .lanes(SubPathFixture.busLane("370", "BL-370")).build(),
                        SubPathFixture.walk(3).names("정류장F", "목적지").distance(150.0).build()),
                List.of());
    }
}
