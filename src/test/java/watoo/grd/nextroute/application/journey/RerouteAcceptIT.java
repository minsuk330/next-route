package watoo.grd.nextroute.application.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateDisplay;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateEnvelope;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.AcceptRerouteProposalUseCase;
import watoo.grd.nextroute.application.journey.port.in.GetJourneyNavigationGeometryUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.application.route.service.TransferStopResolver;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 수락이 <b>실제 여정 위에서</b> 도는지.
 *
 * <p>여기서 지키는 것 넷.
 *
 * <ul>
 *   <li>개정본·명령 번호가 각각 1씩 오르고 <b>앞 구간이 진행도까지 그대로</b> 남는다</li>
 *   <li><b>새 스냅샷을 다시 쪼갠 결과가 저장된 단계와 같다</b> — geometry 와 ④가 서 있는 계약</li>
 *   <li>이전 개정본의 알림이 <b>{@code route-changed}</b> 사유로 취소된다({@code journey-ended} 가 아니다)</li>
 *   <li>같은 후보 재전송은 개정본을 <b>한 번만</b> 올린다</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "journey.reroute.enabled=true"
})
@DisplayName("재탐색 수락")
class RerouteAcceptIT {

    private static final String SESSION_ID = "sess-reroute-accept-it";
    private static final String PATH_KEY = "0";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired AcceptRerouteProposalUseCase acceptUseCase;
    @Autowired GetJourneyNavigationGeometryUseCase geometryUseCase;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean PredictTransferUseCase predictTransferUseCase;
    @MockBean OdSayApiPort odSayApiPort;
    @MockBean TransferStopResolver transferStopResolver;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private JourneySnapshot journey;

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, plannedPath(), Instant.now().plusSeconds(600))));
        given(transferStopResolver.resolveStop(anyString())).willAnswer(call ->
                Optional.of(new TransferStopResolver.StopResolution(call.getArgument(0))));
        given(transferStopResolver.resolveRoute(anyString(), any())).willAnswer(call ->
                Optional.of(new TransferStopResolver.RouteResolution(
                        call.getArgument(0), call.getArgument(1), false, null)));
        given(transferStopResolver.resolveSeq(anyString(), anyString()))
                .willReturn(new TransferStopResolver.SeqResolution(List.of(3)));
        userId = newUser();
        journey = startJourneyUseCase.start(userId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    @Test
    @DisplayName("수락하면 개정본과 명령 번호가 각각 1씩 오르고 새 경로가 저장된다")
    void acceptBumpsRevisionAndStoresNewPath() {
        String candidateId = givenProposal();

        JourneySnapshot after = acceptUseCase.accept(userId, journey.journeyId(), proposalId(),
                candidateId);

        assertThat(after.routeRevision()).isEqualTo(journey.routeRevision() + 1);
        assertThat(after.commandVersion()).isEqualTo(journey.commandVersion() + 1);
        assertThat(revisionStatus(journey.routeRevision())).isEqualTo("SUPERSEDED");
        assertThat(revisionReason(after.routeRevision())).isEqualTo("REMAINING_PATH_ACCEPTED");
        assertThat(proposalStatus()).isEqualTo("ACCEPTED");
        assertThat(acceptedCandidateId()).isEqualTo(candidateId);
        // 새 경로의 노선이 들어와 있다.
        assertThat(routeIdsOf(after.routeRevision())).contains("BL-360");
    }

    @Test
    @DisplayName("저장 목록에 없는 동적 노선 후보를 수락하면 새 후보 행을 만들어 그 노선을 고른다 — 첫 후보로 조용히 대체되지 않는다")
    void acceptsDynamicRouteOptionByInsertingNewRow() {
        int waitLegIndex = firstWaitLegIndex();
        // 저장 후보엔 BL-DYNAMIC 이 없다(planned=BL-370/BL-721). 저장에 없는 노선을 동적 후보로 심는다.
        assertThat(routeIdsOf(journey.routeRevision())).doesNotContain("BL-DYNAMIC");
        String candidateId = givenRouteOptionProposal(new RerouteCandidateApply.RouteOptionApply(
                waitLegIndex, RerouteCandidateApply.RouteOptionApply.DYNAMIC,
                "BL-DYNAMIC", "UP", "SRC-DYN", "999", null, 7, 20, null));

        JourneySnapshot after = acceptUseCase.accept(userId, journey.journeyId(), proposalId(),
                candidateId);

        // 새 개정본에 동적 노선 행이 생겼고, 그 행이 선택돼 있다(첫 후보 fallback 아님).
        assertThat(routeIdsOf(after.routeRevision())).contains("BL-DYNAMIC");
        assertThat(selectedRouteIdAt(after.routeRevision(), waitLegIndex)).isEqualTo("BL-DYNAMIC");
    }

    @Test
    @DisplayName("앞 구간은 진행도·시각까지 그대로 남는다 — 재컴파일로 다시 만들지 않는다")
    void keepsPrefixLegsWithProgress() {
        givenProgressOnFirstLeg();
        String candidateId = givenProposal();

        JourneySnapshot after = acceptUseCase.accept(userId, journey.journeyId(), proposalId(),
                candidateId);

        assertThat(jdbcTemplate.queryForObject("""
                select progress_section_id from journey_leg
                 where journey_id = ? and route_revision = ? and leg_index = 0
                """, String.class, journey.journeyId(), after.routeRevision()))
                .isEqualTo("sec-1");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from journey_leg
                 where journey_id = ? and route_revision = ? and activated_at is not null
                """, Integer.class, journey.journeyId(), after.routeRevision()))
                .isPositive();
    }

    @Test
    @DisplayName("새 스냅샷을 다시 쪼갠 결과가 저장된 단계와 같다 — 지도가 조용히 틀리지 않는다")
    void snapshotMatchesStoredLegs() {
        String candidateId = givenProposal();

        JourneySnapshot after = acceptUseCase.accept(userId, journey.journeyId(), proposalId(),
                candidateId);

        // geometry 는 개정본 스냅샷을 다시 쪼개 단계마다 좌표를 붙인다. 단계 수가 어긋나면
        // 여기서 터지거나 좌표가 다른 단계에 붙는다.
        assertThat(geometryUseCase.get(userId, journey.journeyId(), after.routeRevision()).legs())
                .hasSize(legCount(after.routeRevision()));
    }

    @Test
    @DisplayName("이전 개정본의 알림은 route-changed 로 취소된다")
    void cancelsPendingNotificationsWithRouteChanged() {
        long notificationId = givenPendingNotification();
        String candidateId = givenProposal();

        acceptUseCase.accept(userId, journey.journeyId(), proposalId(), candidateId);

        assertThat(jdbcTemplate.queryForObject("""
                select status || ':' || last_failure_reason from journey_notification where id = ?
                """, String.class, notificationId))
                .isEqualTo("CANCELED:route-changed");
    }

    @Test
    @DisplayName("같은 후보로 다시 누르면 200 이고 개정본은 한 번만 오른다")
    void resendKeepsSingleRevision() {
        String candidateId = givenProposal();
        JourneySnapshot first = acceptUseCase.accept(userId, journey.journeyId(), proposalId(),
                candidateId);

        JourneySnapshot second = acceptUseCase.accept(userId, journey.journeyId(), proposalId(),
                candidateId);

        assertThat(second.routeRevision()).isEqualTo(first.routeRevision());
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from journey_revision where journey_id = ?
                """, Integer.class, journey.journeyId())).isEqualTo(2);
    }

    @Test
    @DisplayName("다른 후보로 다시 누르면 409 다")
    void resendWithOtherCandidateConflicts() {
        String candidateId = givenProposal();
        acceptUseCase.accept(userId, journey.journeyId(), proposalId(), candidateId);

        assertThatThrownBy(() -> acceptUseCase.accept(userId, journey.journeyId(), proposalId(),
                UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("이미 지나간 단계부터 갈아끼우는 제안은 409 이고 여정은 그대로다")
    void rejectsWhenReplacementStartsBehind() {
        String candidateId = givenProposal();
        jdbcTemplate.update("update journey_session set current_leg_index = 99 where id = ?",
                journey.journeyId());

        assertThatThrownBy(() -> acceptUseCase.accept(userId, journey.journeyId(), proposalId(),
                candidateId))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", JourneyErrorCode.REROUTE_PROPOSAL_STALE);
        assertThat(routeRevisionOf()).isEqualTo(journey.routeRevision());
        // 거절은 롤백이다. 제안이 소모되면 사용자가 다시 누를 수 없다.
        assertThat(proposalStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("명령 번호가 그 사이 바뀌었으면 409 다")
    void rejectsWhenCommandVersionMoved() {
        String candidateId = givenProposal();
        jdbcTemplate.update("""
                update journey_session set command_version = command_version + 1 where id = ?
                """, journey.journeyId());

        assertThatThrownBy(() -> acceptUseCase.accept(userId, journey.journeyId(), proposalId(),
                candidateId))
                .isInstanceOf(BusinessException.class);
        assertThat(proposalStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("만료된 제안은 409 다")
    void rejectsExpiredProposal() {
        String candidateId = givenProposal();
        jdbcTemplate.update("""
                update reroute_proposal set expires_at = now() - interval '1 minute'
                 where journey_id = ?
                """, journey.journeyId());

        assertThatThrownBy(() -> acceptUseCase.accept(userId, journey.journeyId(), proposalId(),
                candidateId))
                .isInstanceOf(BusinessException.class);
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    /** ④가 만들었을 제안을 손으로 심는다. 후보 생성 자체는 ④의 시험이 본다. */
    private String givenProposal() {
        String candidateId = UUID.randomUUID().toString();
        RerouteCandidate candidate = new RerouteCandidate(candidateId,
                watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind.REMAINING_PATH,
                new RerouteCandidateApply.RemainingPathApply(firstWaitLegIndex(), candidatePath()),
                new RerouteCandidateDisplay(Instant.now().plusSeconds(1800), 1800, 600, 1, 300,
                        "REALTIME", "GOOD", Instant.now(), List.of("360 승차")));
        String candidates;
        try {
            candidates = objectMapper.writeValueAsString(
                    RerouteCandidateEnvelope.of(List.of(candidate)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        jdbcTemplate.update("""
                insert into reroute_proposal
                    (created_at, updated_at, journey_id, user_id, route_revision,
                     kind, status, base_command_version, candidates, snapshot_observed_at,
                     expires_at)
                values (now(), now(), ?, ?, ?, 'REMAINING_PATH', 'ACTIVE', ?, ?::jsonb,
                        now(), now() + interval '2 minutes')
                """, journey.journeyId(), userId, journey.routeRevision(),
                journey.commandVersion(), candidates);
        return candidateId;
    }

    /** ③(route-option)가 만들었을 제안을 손으로 심는다. apply 는 저장/동적 후보 어느 쪽이든 된다. */
    private String givenRouteOptionProposal(RerouteCandidateApply.RouteOptionApply apply) {
        String candidateId = UUID.randomUUID().toString();
        RerouteCandidate candidate = new RerouteCandidate(candidateId,
                watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind.ROUTE_OPTION, apply,
                new RerouteCandidateDisplay(Instant.now().plusSeconds(1500), 1500, 600, 1, 300,
                        "REALTIME", "GOOD", Instant.now(), List.of("999 승차")));
        String candidates;
        try {
            candidates = objectMapper.writeValueAsString(
                    RerouteCandidateEnvelope.of(List.of(candidate)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        jdbcTemplate.update("""
                insert into reroute_proposal
                    (created_at, updated_at, journey_id, user_id, route_revision,
                     kind, status, base_command_version, candidates, snapshot_observed_at,
                     expires_at)
                values (now(), now(), ?, ?, ?, 'ROUTE_OPTION', 'ACTIVE', ?, ?::jsonb,
                        now(), now() + interval '2 minutes')
                """, journey.journeyId(), userId, journey.routeRevision(),
                journey.commandVersion(), candidates);
        return candidateId;
    }

    private String selectedRouteIdAt(int revision, int legIndex) {
        return jdbcTemplate.queryForObject("""
                select o.route_id from journey_leg_route_option o
                  join journey_leg l on l.selected_route_option_id = o.id
                 where l.journey_id = ? and l.route_revision = ? and l.leg_index = ?
                """, String.class, journey.journeyId(), revision, legIndex);
    }

    private void givenProgressOnFirstLeg() {
        jdbcTemplate.update("""
                update journey_leg
                   set progress_section_id = 'sec-1', progress_fraction = 0.5,
                       progress_observed_at = now(), activated_at = now()
                 where journey_id = ? and route_revision = ? and leg_index = 0
                """, journey.journeyId(), journey.routeRevision());
    }

    private long givenPendingNotification() {
        String key = "key-" + UUID.randomUUID();
        return jdbcTemplate.queryForObject("""
                insert into journey_notification
                    (created_at, updated_at, journey_id, user_id, route_revision, scope_type,
                     scope_id, checkpoint, channel, command_version, evaluation_version,
                     logical_checkpoint_key, delivery_key, status, attempt_count,
                     scheduled_at, expires_at)
                values (now(), now(), ?, ?, ?, 'TRANSFER', 0, 'TRANSFER_RISK', 'PUSH', ?, 0,
                        ?, ?, 'PENDING', 0, now(), now() + interval '10 minutes')
                returning id
                """, Long.class, journey.journeyId(), userId, journey.routeRevision(),
                journey.commandVersion(), key, key + ":PUSH");
    }

    private int firstWaitLegIndex() {
        return jdbcTemplate.queryForObject("""
                select t.to_leg_index from transfer_connection t
                 where t.journey_id = ? and t.transfer_index = 0 and t.deleted_at is null
                """, Integer.class, journey.journeyId());
    }

    private Long proposalId() {
        return jdbcTemplate.queryForObject("""
                select id from reroute_proposal where journey_id = ? order by id desc limit 1
                """, Long.class, journey.journeyId());
    }

    private String proposalStatus() {
        return jdbcTemplate.queryForObject("""
                select status from reroute_proposal where journey_id = ? order by id desc limit 1
                """, String.class, journey.journeyId());
    }

    private String acceptedCandidateId() {
        return jdbcTemplate.queryForObject("""
                select accepted_candidate_id from reroute_proposal
                 where journey_id = ? order by id desc limit 1
                """, String.class, journey.journeyId());
    }

    private String revisionStatus(int revision) {
        return jdbcTemplate.queryForObject("""
                select status from journey_revision where journey_id = ? and route_revision = ?
                """, String.class, journey.journeyId(), revision);
    }

    private String revisionReason(int revision) {
        return jdbcTemplate.queryForObject("""
                select reason from journey_revision where journey_id = ? and route_revision = ?
                """, String.class, journey.journeyId(), revision);
    }

    private int routeRevisionOf() {
        return jdbcTemplate.queryForObject("""
                select route_revision from journey_session where id = ?
                """, Integer.class, journey.journeyId());
    }

    private int legCount(int revision) {
        return jdbcTemplate.queryForObject("""
                select count(*) from journey_leg
                 where journey_id = ? and route_revision = ? and deleted_at is null
                """, Integer.class, journey.journeyId(), revision);
    }

    private List<String> routeIdsOf(int revision) {
        return jdbcTemplate.queryForList("""
                select o.route_id from journey_leg_route_option o
                  join journey_leg l on l.id = o.journey_leg_id
                 where l.journey_id = ? and l.route_revision = ?
                """, String.class, journey.journeyId(), revision);
    }

    /** 버스 둘, 환승 하나. */
    private PathResult plannedPath() {
        return new PathResult(1,
                new PathInfo(60, 4000, 900, 1, "정류장A", "정류장Z", null),
                List.of(
                        SubPathFixture.bus(20).names("정류장A", "정류장F")
                                .coords(127.00, 37.00, 127.10, 37.10)
                                .busStops("ST-A", "ST-F")
                                .lanes(SubPathFixture.busLane("370", "BL-370")).build(),
                        SubPathFixture.walk(3).names("정류장F", "정류장G").distance(150.0)
                                .coords(127.10, 37.10, 127.20, 37.20).build(),
                        SubPathFixture.bus(40).names("정류장G", "정류장Z")
                                .coords(127.20, 37.20, 127.50, 37.50)
                                .busStops("ST-G", "ST-Z")
                                .lanes(SubPathFixture.busLane("721", "BL-721")).build()),
                List.of());
    }

    /** 재검색이 준 다른 경로. 같은 환승 정류장에서 출발한다. */
    private PathResult candidatePath() {
        return new PathResult(1,
                new PathInfo(25, 3800, 300, 0, "정류장G", "정류장Z", null),
                List.of(SubPathFixture.bus(15).names("정류장G", "정류장Z")
                        .coords(127.20, 37.20, 127.50, 37.50)
                        .busStops("ST-G", "ST-Z")
                        .lanes(SubPathFixture.busLane("360", "BL-360")).build()),
                List.of());
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
        jdbcTemplate.update("""
                delete from journey_notification where journey_id in (
                    select id from journey_session where user_id = ?)
                """, id);
        jdbcTemplate.update("delete from journey_session where user_id = ?", id);
    }
}
