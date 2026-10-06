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
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateRoute;
import watoo.grd.nextroute.application.journey.port.in.AcceptRerouteProposalUseCase;
import watoo.grd.nextroute.application.journey.port.in.GetRerouteCandidateRouteUseCase;
import watoo.grd.nextroute.application.journey.dto.RerouteBoardSlot;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginKind;
import watoo.grd.nextroute.application.journey.service.RerouteBoardMerger;
import watoo.grd.nextroute.application.journey.service.RerouteBoardService;
import watoo.grd.nextroute.application.journey.service.RerouteBoardRefreshClaimService;
import watoo.grd.nextroute.application.journey.service.RerouteBoardRefreshService;
import watoo.grd.nextroute.application.journey.service.RerouteRefreshSignal;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.JourneyProgressCommand;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyProgressUseCase;
import watoo.grd.nextroute.application.journey.port.in.GetJourneyNavigationGeometryUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.application.route.service.TransferStopResolver;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;
import watoo.grd.nextroute.domain.bus.repository.RouteStopProjection;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    /** 타고 있는 버스의 노선 정류장: 승차 1(ST-A) … 중간 4(ST-D) … 계획 하차 5(ST-F). */
    private static final int MID_STOP_SEQ = 4;
    private static final String MID_STOP_ID = "ST-D";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired AcceptRerouteProposalUseCase acceptUseCase;
    @Autowired GetRerouteCandidateRouteUseCase candidateRouteUseCase;
    @Autowired RerouteBoardService boardService;
    @Autowired RerouteRefreshSignal refreshSignal;
    @Autowired RerouteBoardRefreshClaimService refreshClaimService;
    @Autowired RerouteBoardRefreshService refreshService;
    @Autowired JourneyRerouteProperties rerouteProperties;
    @Autowired ApplyJourneyProgressUseCase progressUseCase;
    @Autowired watoo.grd.nextroute.application.journey.service.RerouteProposalService proposalService;
    @Autowired GetJourneyNavigationGeometryUseCase geometryUseCase;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean PredictTransferUseCase predictTransferUseCase;
    @MockBean OdSayApiPort odSayApiPort;
    @MockBean TransferStopResolver transferStopResolver;
    @MockBean BusRouteStopRepository busRouteStopRepository;
    @MockBean SearchTimeBusQueryPort busQueryPort;

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
        rerouteProperties.getBoard().setEnabled(false);
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
        // 수락은 한 여정에 한 번이다(PR-3). 고른 길로 막 바꿨는데 또 바꾸라고 알리면 피로가
        // 남은 이득보다 크다.
        assertThat(jdbcTemplate.queryForObject(
                "select reroute_terminated from journey_session where id = ?",
                Boolean.class, journey.journeyId())).isTrue();
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
    @DisplayName("주행 중 중간 하차를 수락하면 타던 구간이 잘려 남고 진행도는 살아 있다")
    void acceptsMidRideAlightKeepingProgress() {
        givenRidingWithProgress();
        String candidateId = givenMidRideProposal(MID_STOP_SEQ);

        JourneySnapshot after = acceptUseCase.accept(userId, journey.journeyId(), proposalId(),
                candidateId);

        assertThat(revisionReason(after.routeRevision()))
                .isEqualTo("REMAINING_PATH_MID_RIDE_ACCEPTED");
        // 타던 구간은 사라지지 않는다. 하차 목표만 중간 정류장으로 당겨진다.
        assertThat(stringOfLeg(after.routeRevision(), 1, "end_stop_id")).isEqualTo(MID_STOP_ID);
        assertThat(stringOfLeg(after.routeRevision(), 1, "end_name")).isEqualTo("정류장D");
        assertThat(stringOfLeg(after.routeRevision(), 1, "state")).isEqualTo("ACTIVE");
        // 진행도·시각은 그대로다. 잃으면 앱의 GPS 엔진이 이 구간을 처음부터 다시 센다.
        assertThat(stringOfLeg(after.routeRevision(), 1, "progress_section_id"))
                .isEqualTo("sec-ride");
        assertThat(intOfLeg(after.routeRevision(), 1, "progress_geometry_segment_index"))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                select activated_at is not null from journey_leg
                 where journey_id = ? and route_revision = ? and leg_index = 1
                """, Boolean.class, journey.journeyId(), after.routeRevision())).isTrue();
        // 소요는 승차 시각부터 잰 초 단위 실측이다(10분 전에 탔고 5분 뒤 내린다). 스냅샷의 분
        // 단위를 되돌린 값이 아니다.
        assertThat(intOfLeg(after.routeRevision(), 1, "planned_duration_sec")).isBetween(920, 940);
        // 옛 하차점 기준의 값들은 새 하차점 기준으로 다시 잰다.
        assertThat(intOfLeg(after.routeRevision(), 1, "remaining_stops")).isEqualTo(1);
        assertThat(intOfLeg(after.routeRevision(), 1, "remaining_distance_meters")).isNull();
        // 진행 비율도 잘린 구간(1→4번 정류장) 기준으로 다시 잰다. 옛 값(0.8)을 남기면 화면이
        // "거의 다 왔다"고 말한다.
        assertThat(jdbcTemplate.queryForObject("""
                select progress_leg_fraction from journey_leg
                 where journey_id = ? and route_revision = ? and leg_index = 1
                """, java.math.BigDecimal.class, journey.journeyId(), after.routeRevision()))
                .isEqualByComparingTo("0.666667");
        // 그 뒤로 새 경로가 붙는다.
        assertThat(routeIdsOf(after.routeRevision())).contains("BL-471");
    }

    @Test
    @DisplayName("누르는 사이 그 정류장을 지나갔으면 거절하고 여정은 그대로다")
    void rejectsMidRideAlightAfterBusPassedIt() {
        givenRidingWithProgress();
        // 지금 막 지난 정류장(seq 3)에서 내리라는 제안이다. 그대로 반영하면 사용자는 못 내린다.
        String candidateId = givenMidRideProposal(3);

        assertThatThrownBy(() -> acceptUseCase.accept(userId, journey.journeyId(), proposalId(),
                candidateId))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(JourneyErrorCode.REROUTE_ALIGHT_UNAVAILABLE);

        // 거절은 롤백이다 — 제안도 여정도 소모되지 않는다.
        assertThat(routeRevisionOf()).isEqualTo(journey.routeRevision());
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

    // ── 후보 경로 조회(#259) ────────────────────────────────────────────────

    @Test
    @DisplayName("조회한 경로는 저장된 적용 재료 그대로이고, 같은 후보를 수락하면 그 경로가 적용된다")
    void candidateRouteIsWhatAcceptApplies() throws Exception {
        String candidateId = givenProposal();

        RerouteCandidateRoute route = candidateRouteUseCase.get(userId, journey.journeyId(),
                proposalId(), candidateId);

        // 저장 행(jsonb)에서 직접 꺼낸 적용 재료와 비교한다 — 조회가 다시 검색하거나 가공하면
        // 사용자가 본 경로와 다른 경로가 적용된다.
        assertThat((Object) objectMapper.valueToTree(route.path()))
                .isEqualTo(storedApplyPath(candidateId));
        // 조회는 제안을 소모하지 않는다. 여러 후보를 열어 본 뒤 고른다.
        assertThat(proposalStatus()).isEqualTo("ACTIVE");

        JourneySnapshot after = acceptUseCase.accept(userId, journey.journeyId(), proposalId(),
                candidateId);
        assertThat(routeIdsOf(after.routeRevision())).contains("BL-360");
    }

    @Test
    @DisplayName("중간 하차 후보는 지금 타는 구간과 남은 정류장 수를 함께 준다")
    void candidateRouteCarriesCurrentRide() {
        givenRidingWithProgress();
        jdbcTemplate.update("update journey_session set current_leg_index = 1 where id = ?",
                journey.journeyId());
        String candidateId = givenMidRideProposal(MID_STOP_SEQ);

        RerouteCandidateRoute route = candidateRouteUseCase.get(userId, journey.journeyId(),
                proposalId(), candidateId);

        assertThat(route.ride()).isNotNull();
        assertThat(route.ride().legIndex()).isEqualTo(1);
        assertThat(route.ride().alightStopId()).isEqualTo(MID_STOP_ID);
        // 계획 하차 5번, 남은 정류장 2 → 지금 3번을 지났다. 4번까지 한 정류장.
        assertThat(route.ride().stopCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("만료된 제안의 경로 조회는 REROUTE_PROPOSAL_STALE 이다")
    void candidateRouteOfExpiredProposalIsStale() {
        String candidateId = givenProposal();
        jdbcTemplate.update("""
                update reroute_proposal set expires_at = now() - interval '1 minute'
                 where journey_id = ?
                """, journey.journeyId());

        assertThatThrownBy(() -> candidateRouteUseCase.get(userId, journey.journeyId(),
                proposalId(), candidateId))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(JourneyErrorCode.REROUTE_PROPOSAL_STALE);
    }

    // ── 보드(#264, reroute-periodic-board PR-1) ─────────────────────────────

    @Test
    @DisplayName("보드는 한 행을 제자리에서 고친다 — 처음엔 심고, 다음 회차는 판을 올린다")
    void boardIsUpdatedInPlace() {
        RerouteBoardService.Written first = boardService.write(boardWrite(600), Instant.now());
        Long boardId = proposalId();
        RerouteBoardService.Written second = boardService.write(boardWrite(660), Instant.now());

        assertThat(first.outcome()).isEqualTo(RerouteBoardService.Outcome.INSERTED);
        assertThat(second.outcome()).isEqualTo(RerouteBoardService.Outcome.UPDATED);
        assertThat(proposalId()).isEqualTo(boardId);
        assertThat(jdbcTemplate.queryForObject(
                "select board_version from reroute_proposal where id = ?", Long.class, boardId))
                .isEqualTo(2L);
        // 같은 경로라 후보 id 가 이어진다 — 사용자가 보던 것을 그대로 고를 수 있다.
        assertThat(second.slots().get(0).candidate().candidateId())
                .isEqualTo(first.slots().get(0).candidate().candidateId());
    }

    @Test
    @DisplayName("구간 전이 뒤 갱신된 보드의 후보는 수락된다 — 명령 번호를 보드가 다시 찍는다")
    void boardCandidateIsAcceptableAfterLegTransition() {
        boardService.write(boardWrite(600), Instant.now());
        // 사용자가 탑승·하차를 알렸다(명령 번호 +1). 보드는 그 뒤 다시 갱신된다.
        jdbcTemplate.update("update journey_session set command_version = command_version + 1 where id = ?",
                journey.journeyId());
        RerouteBoardService.Written refreshed = boardService.write(
                boardWrite(600, journey.commandVersion() + 1), Instant.now());

        JourneySnapshot after = acceptUseCase.accept(userId, journey.journeyId(), proposalId(),
                refreshed.slots().get(0).candidate().candidateId());

        assertThat(after.routeRevision()).isEqualTo(journey.routeRevision() + 1);
    }

    @Test
    @DisplayName("계산하는 사이 여정이 움직였으면 저장하지 않는다")
    void staleContextIsDropped() {
        jdbcTemplate.update("update journey_session set command_version = command_version + 1 where id = ?",
                journey.journeyId());

        RerouteBoardService.Written written = boardService.write(boardWrite(600), Instant.now());

        assertThat(written.outcome()).isEqualTo(RerouteBoardService.Outcome.STALE_CONTEXT);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from reroute_proposal where journey_id = ?", Integer.class,
                journey.journeyId())).isZero();
    }

    @Test
    @DisplayName("사건 기반 경로가 남긴 v1 제안은 보드로 대체된다")
    void legacyProposalIsSupersededByBoard() {
        givenProposal();
        Long legacyId = proposalId();

        RerouteBoardService.Written written = boardService.write(boardWrite(600), Instant.now());

        assertThat(written.outcome()).isEqualTo(RerouteBoardService.Outcome.INSERTED);
        assertThat(jdbcTemplate.queryForObject(
                "select status from reroute_proposal where id = ?", String.class, legacyId))
                .isEqualTo("SUPERSEDED");
    }

    @Test
    @DisplayName("숨긴 자리의 후보는 고를 수 없다")
    void hiddenSlotIsNotSelectable() {
        String shownId = boardService.write(boardWrite(600), Instant.now())
                .slots().get(0).candidate().candidateId();
        // 이득이 문턱 아래로 떨어져 숨겨진다.
        boardService.write(boardWrite(60), Instant.now());

        assertThatThrownBy(() -> candidateRouteUseCase.get(userId, journey.journeyId(),
                proposalId(), shownId))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(JourneyErrorCode.REROUTE_CANDIDATE_NOT_FOUND);
    }

    @Test
    @DisplayName("수락이 먼저 이기면 그 뒤 도착한 갱신은 저장되지 않는다 — 세션 잠금으로 줄을 선다")
    void refreshAfterAcceptIsDropped() {
        String candidateId = boardService.write(boardWrite(600), Instant.now())
                .slots().get(0).candidate().candidateId();
        // 수락 전에 계산을 시작한 회차 — 그 시점의 여정 사진으로 만들어진다.
        RerouteBoardService.BoardWrite inFlight = boardWrite(700);
        acceptUseCase.accept(userId, journey.journeyId(), proposalId(), candidateId);

        RerouteBoardService.Written late = boardService.write(inFlight, Instant.now());

        assertThat(late.stored()).isFalse();
        assertThat(proposalStatus()).isEqualTo("ACCEPTED");
    }

    // ── 보드 갱신 예약·선점(#264 PR-2) ──────────────────────────────────────

    @Test
    @DisplayName("플래그가 꺼져 있으면 갱신 신호는 아무것도 쓰지 않는다")
    void signalIsInertWhenBoardDisabled() {
        refreshSignal.requestNow(journey.journeyId());

        assertThat(refreshAtSecondsFromNow()).isNull();
    }

    @Test
    @DisplayName("신호는 예정을 지금으로 당긴다 — 직전 갱신이 30초 안이면 그 뒤로, 위험 신호는 우회한다")
    void signalRespectsMinGapUnlessUrgent() {
        rerouteProperties.getBoard().setEnabled(true);
        jdbcTemplate.update("""
                update journey_session set reroute_last_refresh_at = now() - interval '10 seconds',
                       reroute_refresh_at = now() + interval '10 minutes' where id = ?
                """, journey.journeyId());

        refreshSignal.requestNow(journey.journeyId());
        assertThat(refreshAtSecondsFromNow()).isBetween(15.0, 25.0);

        refreshSignal.requestUrgent(journey.journeyId());
        assertThat(refreshAtSecondsFromNow()).isBetween(-5.0, 2.0);
    }

    @Test
    @DisplayName("선점 중에 온 신호는 예정을 건드리지 않고 표시만 남긴다 — 두 워커가 같은 여정을 돌지 않게")
    void signalDuringClaimOnlyMarksDirty() {
        rerouteProperties.getBoard().setEnabled(true);
        jdbcTemplate.update("""
                update journey_session set reroute_refresh_at = now() - interval '1 second' where id = ?
                """, journey.journeyId());
        RerouteBoardRefreshClaimService.Claim claim = claimMine();

        refreshSignal.requestUrgent(journey.journeyId());

        assertThat(jdbcTemplate.queryForObject(
                "select reroute_refresh_dirty from journey_session where id = ?", Boolean.class,
                journey.journeyId())).isTrue();
        // 다른 워커는 같은 여정을 다시 잡지 못한다.
        assertThat(refreshClaimService.claim()).extracting(RerouteBoardRefreshClaimService.Claim::journeyId)
                .doesNotContain(journey.journeyId());
        // 마무리는 표시를 보고 최소 간격 뒤로 잡는다.
        assertThat(refreshClaimService.finish(claim)).isTrue();
        assertThat(refreshAtSecondsFromNow()).isBetween(25.0, 35.0);
    }

    @Test
    @DisplayName("마무리는 내 선점일 때만 쓰고, 신호가 없었으면 기본 주기 뒤로 잡는다")
    void finishOnlyWithOwnLease() {
        rerouteProperties.getBoard().setEnabled(true);
        jdbcTemplate.update("""
                update journey_session set reroute_refresh_at = now() - interval '1 second' where id = ?
                """, journey.journeyId());
        RerouteBoardRefreshClaimService.Claim claim = claimMine();

        assertThat(refreshClaimService.finish(new RerouteBoardRefreshClaimService.Claim(
                claim.journeyId(), claim.leaseUntil().plusSeconds(1)))).isFalse();
        assertThat(refreshClaimService.finish(claim)).isTrue();
        assertThat(refreshAtSecondsFromNow()).isBetween(55.0, 65.0);
    }

    @Test
    @DisplayName("여정을 시작하면 첫 갱신이 시작 + 60초로 잡힌다")
    void startSchedulesInitialRefresh() {
        rerouteProperties.getBoard().setEnabled(true);
        Long otherUser = newUser();

        JourneySnapshot started = startJourneyUseCase.start(otherUser,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));

        assertThat(jdbcTemplate.queryForObject("""
                select extract(epoch from (reroute_refresh_at - now())) from journey_session where id = ?
                """, Double.class, started.journeyId())).isBetween(55.0, 65.0);
    }

    @Test
    @DisplayName("남은 정류장 수가 줄면 보드를 바로 다시 찾게 한다. 그대로면 예정을 건드리지 않는다")
    void stopPassedProgressRequestsRefresh() {
        rerouteProperties.getBoard().setEnabled(true);
        applyProgress(3, Instant.now().minusSeconds(20));
        jdbcTemplate.update("""
                update journey_session set reroute_last_refresh_at = now() - interval '5 minutes',
                       reroute_refresh_at = now() + interval '10 minutes' where id = ?
                """, journey.journeyId());

        applyProgress(3, Instant.now().minusSeconds(10));
        assertThat(refreshAtSecondsFromNow()).isGreaterThan(500.0);

        applyProgress(2, Instant.now().minusSeconds(1));
        assertThat(refreshAtSecondsFromNow()).isBetween(-5.0, 2.0);
    }

    @Test
    @DisplayName("갱신 한 회차가 보드(v2) 행을 남긴다 — 후보가 없어도 '찾아봤다'가 기록된다")
    void refreshWritesBoardRow() {
        rerouteProperties.getBoard().setEnabled(true);

        RerouteBoardService.Written written = refreshService.refresh(journey.journeyId()).orElseThrow();

        assertThat(written.stored()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                select (candidates->>'version')::int from reroute_proposal
                 where journey_id = ? and status = 'ACTIVE'
                """, Integer.class, journey.journeyId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                select refreshed_at is not null from reroute_proposal
                 where journey_id = ? and status = 'ACTIVE'
                """, Boolean.class, journey.journeyId())).isTrue();
    }

    @Test
    @DisplayName("보드 모드 조회는 보이는 후보와 보드 정보를 주고, 폴링이 행을 바꾸지 않는다(#264 PR-6)")
    void boardModeActiveReadsBoardWithoutWriting() {
        rerouteProperties.getBoard().setEnabled(true);
        String shownId = boardService.write(boardWrite(600), Instant.now())
                .slots().get(0).candidate().candidateId();
        String before = jdbcTemplate.queryForObject(
                "select updated_at::text from reroute_proposal where id = ?", String.class, proposalId());

        var view = proposalService.findActive(userId, journey.journeyId()).orElseThrow();

        assertThat(view.isBoard()).isTrue();
        assertThat(view.board().version()).isEqualTo(1L);
        assertThat(view.candidates()).extracting(RerouteCandidate::candidateId).containsExactly(shownId);
        assertThat(view.board().originKinds()).containsEntry(shownId, RerouteOriginKind.TRANSFER_WAIT);
        assertThat(jdbcTemplate.queryForObject(
                "select updated_at::text from reroute_proposal where id = ?", String.class, proposalId()))
                .isEqualTo(before);
    }

    @Test
    @DisplayName("걷는 중 현재 위치에서 찾은 후보는 그 도보 단계부터 갈아끼워 수락된다(#264 PR-3)")
    void currentPositionCandidateIsAcceptedFromWalkLeg() {
        String slot = RerouteBoardSlot.HERE;
        RerouteCandidate candidate = new RerouteCandidate(null,
                watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind.REMAINING_PATH,
                new RerouteCandidateApply.RemainingPathApply(0, candidatePath(),
                        new watoo.grd.nextroute.application.journey.dto.RerouteOrigin(0, null, null, null,
                                RerouteOriginKind.CURRENT_POSITION, 127.0, 37.5)),
                new RerouteCandidateDisplay(Instant.now().plusSeconds(1800), 1800, 600, 1, 300,
                        "REALTIME", "GOOD", Instant.now(), List.of("360 승차")));
        String candidateId = boardService.write(new RerouteBoardService.BoardWrite(journey.journeyId(), userId,
                journey.routeRevision(), journey.commandVersion(), 0, Instant.now(),
                new RerouteBoardMerger.Input(java.util.Map.of(slot, new RerouteBoardMerger.Evaluated(slot,
                        RerouteOriginKind.CURRENT_POSITION, candidate, 600, true, 0, "fp-here", null)),
                        java.util.Set.of())), Instant.now()).slots().get(0).candidate().candidateId();

        JourneySnapshot after = acceptUseCase.accept(userId, journey.journeyId(), proposalId(), candidateId);

        assertThat(after.routeRevision()).isEqualTo(journey.routeRevision() + 1);
        assertThat(routeIdsOf(after.routeRevision())).contains("BL-360");
    }

    private RerouteBoardRefreshClaimService.Claim claimMine() {
        return refreshClaimService.claim().stream()
                .filter(claim -> claim.journeyId().equals(journey.journeyId()))
                .findFirst()
                .orElseThrow();
    }

    private Double refreshAtSecondsFromNow() {
        return jdbcTemplate.queryForObject("""
                select extract(epoch from (reroute_refresh_at - now())) from journey_session where id = ?
                """, Double.class, journey.journeyId());
    }

    private void applyProgress(int remainingStops, Instant observedAt) {
        progressUseCase.apply(userId, journey.journeyId(), new JourneyProgressCommand(
                UUID.randomUUID(), journey.routeRevision(), 0, 0,
                java.math.BigDecimal.valueOf(0.5), java.math.BigDecimal.valueOf(0.5), null,
                500, 300, remainingStops, observedAt, "GOOD"));
    }

    private RerouteBoardService.BoardWrite boardWrite(int gainSec) {
        return boardWrite(gainSec, journey.commandVersion());
    }

    /** 계획 하차점 자리 하나를 평가한 결과로 보드를 쓴다. 경로는 {@link #givenProposal} 과 같다. */
    private RerouteBoardService.BoardWrite boardWrite(int gainSec, long commandVersion) {
        String slot = RerouteBoardSlot.stopKey("SEOUL", "ST-F");
        RerouteCandidate candidate = new RerouteCandidate(null,
                watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind.REMAINING_PATH,
                new RerouteCandidateApply.RemainingPathApply(firstWaitLegIndex(), candidatePath()),
                new RerouteCandidateDisplay(Instant.now().plusSeconds(1800), 1800, gainSec, 1, 300,
                        "REALTIME", "GOOD", Instant.now(), List.of("360 승차")));
        return new RerouteBoardService.BoardWrite(journey.journeyId(), userId,
                journey.routeRevision(), commandVersion, 0, Instant.now(),
                new RerouteBoardMerger.Input(java.util.Map.of(slot, new RerouteBoardMerger.Evaluated(
                        slot, RerouteOriginKind.TRANSFER_WAIT, candidate, gainSec, true, 0,
                        "fp-360", Instant.now().plusSeconds(900))), java.util.Set.of()));
    }

    /** 저장 행에 든 그 후보의 {@code apply.path}. */
    private com.fasterxml.jackson.databind.JsonNode storedApplyPath(String candidateId)
            throws Exception {
        String json = jdbcTemplate.queryForObject(
                "select candidates::text from reroute_proposal where id = ?",
                String.class, proposalId());
        for (com.fasterxml.jackson.databind.JsonNode candidate
                : objectMapper.readTree(json).get("candidates")) {
            if (candidateId.equals(candidate.get("candidateId").asText())) {
                return candidate.get("apply").get("path");
            }
        }
        throw new IllegalStateException("후보가 없다: " + candidateId);
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    /**
     * 첫 버스를 타고 가는 중이고 앱이 진행을 보고한 상태로 만든다(PR-4).
     *
     * <p>노선 정류장·실시간 도착은 <b>서버가 재확인에 쓰는 재료</b>라 여기서 심어야 한다 —
     * 이것이 없으면 중간 하차는 "실시간이 없다"로 거절되는 것이 정상 동작이다.
     */
    private void givenRidingWithProgress() {
        jdbcTemplate.update("""
                update journey_leg
                   set state = 'ACTIVE', activated_at = now() - interval '10 minutes',
                       remaining_stops = 2, progress_observed_at = now(),
                       progress_fraction = 0.4, progress_leg_fraction = 0.8,
                       progress_section_id = 'sec-ride', progress_geometry_segment_index = 2,
                       remaining_duration_seconds = 600, remaining_distance_meters = 1200
                 where journey_id = ? and route_revision = ? and leg_index = 1
                """, journey.journeyId(), journey.routeRevision());

        String routeId = stringOfLeg(journey.routeRevision(), 1, "route_id");
        given(busRouteStopRepository.findStopsByRouteId(routeId)).willReturn(routeStops());
        // 기본은 "막혔다" — 새 노선의 승차 추정까지 여기서 답하면 무엇을 시험하는지 흐려진다.
        given(busQueryPort.getArrInfoByStop(anyString(), anyString(), anyInt(), anyLong()))
                .willReturn(SearchTimeBusQueryPort.BusQueryResult.blocked());
        given(busQueryPort.getArrInfoByStop(eq(MID_STOP_ID), eq(routeId), eq(MID_STOP_SEQ),
                anyLong()))
                .willReturn(SearchTimeBusQueryPort.BusQueryResult.ok(List.of(midStopArrival())));
    }

    /** 중간 정류장에서 내려 다른 버스로 갈아타는 후보를 심는다. */
    private String givenMidRideProposal(int alightSeq) {
        String routeId = stringOfLeg(journey.routeRevision(), 1, "route_id");
        RerouteCandidateApply apply = new RerouteCandidateApply.RemainingPathApply(
                1, midRidePath(),
                new watoo.grd.nextroute.application.journey.dto.RerouteOrigin(
                        1, alightSeq == MID_STOP_SEQ ? MID_STOP_ID : "ST-" + alightSeq,
                        routeId, alightSeq,
                        watoo.grd.nextroute.application.journey.dto.RerouteOriginKind.RIDE_INTERIOR));
        return givenProposalOf(apply, "REMAINING_PATH");
    }

    /** ④가 만들었을 제안을 손으로 심는다. 후보 생성 자체는 ④의 시험이 본다. */
    private String givenProposal() {
        return givenProposalOf(
                new RerouteCandidateApply.RemainingPathApply(firstWaitLegIndex(), candidatePath()),
                "REMAINING_PATH");
    }

    /** 후보 하나짜리 ACTIVE 제안을 심는다. 화면 값은 수락 경로가 읽지 않으므로 고정값이다. */
    private String givenProposalOf(RerouteCandidateApply apply, String kind) {
        String candidateId = UUID.randomUUID().toString();
        RerouteCandidate candidate = new RerouteCandidate(candidateId,
                watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind.valueOf(kind),
                apply,
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
                values (now(), now(), ?, ?, ?, ?, 'ACTIVE', ?, ?::jsonb,
                        now(), now() + interval '2 minutes')
                """, journey.journeyId(), userId, journey.routeRevision(), kind,
                journey.commandVersion(), candidates);
        return candidateId;
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
                                // 정류장별 목록이 있어야 중간 하차가 가능하다(PR-C). 순서는 노선
                                // 순번 1~5 와 짝이다 — 그 차이가 곧 자를 자리다.
                                .stations(new StationResult(0, "OTP:A", "정류장A", 127.00, 37.00),
                                        new StationResult(1, "OTP:B", "정류장B", 127.02, 37.02),
                                        new StationResult(2, "OTP:C", "정류장C", 127.04, 37.04),
                                        new StationResult(3, "OTP:D", "정류장D", 127.06, 37.06),
                                        new StationResult(4, "OTP:F", "정류장F", 127.10, 37.10))
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

    /** 중간 정류장(정류장D)에서 목적지까지 다른 버스 하나. */
    private PathResult midRidePath() {
        return new PathResult(1,
                new PathInfo(30, 3900, 400, 0, "정류장D", "정류장Z", null),
                List.of(SubPathFixture.bus(18).names("정류장D", "정류장Z")
                        .coords(127.06, 37.06, 127.50, 37.50)
                        .busStops(MID_STOP_ID, "ST-Z")
                        .lanes(SubPathFixture.busLane("471", "BL-471")).build()),
                List.of());
    }

    /** 타고 있는 노선의 정류장 목록. 이름은 스냅샷 정류장 목록과 짝이어야 한다(이름 대조). */
    private List<RouteStopProjection> routeStops() {
        List<String> ids = List.of("ST-A", "ST-B", "ST-C", MID_STOP_ID, "ST-F");
        List<String> names = List.of("정류장A", "정류장B", "정류장C", "정류장D", "정류장F");
        List<RouteStopProjection> stops = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            stops.add(routeStop(i + 1, ids.get(i), names.get(i)));
        }
        return stops;
    }

    private RouteStopProjection routeStop(int seq, String stopId, String stopName) {
        return new RouteStopProjection() {
            @Override public Integer getSeq() { return seq; }
            @Override public String getStopId() { return stopId; }
            @Override public String getStopName() { return stopName; }
            @Override public Double getLatitude() { return 37.0 + seq / 100.0; }
            @Override public Double getLongitude() { return 127.0 + seq / 100.0; }
            @Override public String getDirection() { return "방면"; }
        };
    }

    /**
     * 내 차(구간 순번 3 = 방금 지난 정류장)가 중간 정류장에 서기까지 330초.
     *
     * <p>분으로 접으면 16분(960초)이 되는 값을 일부러 골랐다 — 저장된 소요가 930초여야
     * "스냅샷에서 되돌린 값이 아니라 실측 초"임이 시험으로 갈린다.
     */
    private BusArrivalInfo midStopArrival() {
        int afterSec = 330;
        return new BusArrivalInfo(
                "BL-370", "RIDE", "370", MID_STOP_ID, "01234", "정류장D", MID_STOP_SEQ, "방면", 3, 10,
                DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                        .format(ZonedDateTime.now(ZoneId.of("Asia/Seoul"))),
                "N", "N", "05:00", "23:00", afterSec + "초 후", "VEH-1",
                "서울70사1234", 1, 3, "직전정류장", "0", "0", "0", afterSec, null, null, null,
                null, null, null, null, afterSec, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    private String stringOfLeg(int revision, int legIndex, String column) {
        return jdbcTemplate.queryForObject("select " + column + """
                 from journey_leg
                 where journey_id = ? and route_revision = ? and leg_index = ?
                """, String.class, journey.journeyId(), revision, legIndex);
    }

    private Integer intOfLeg(int revision, int legIndex, String column) {
        return jdbcTemplate.queryForObject("select " + column + """
                 from journey_leg
                 where journey_id = ? and route_revision = ? and leg_index = ?
                """, Integer.class, journey.journeyId(), revision, legIndex);
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
