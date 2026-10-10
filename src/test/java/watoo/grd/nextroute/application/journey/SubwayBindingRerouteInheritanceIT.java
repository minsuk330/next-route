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
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.application.route.service.TransferStopResolver;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 재탐색으로 개정본이 오를 때 고정을 <b>언제 이어받고 언제 버리는지</b>.
 *
 * <p>양쪽 다 틀리면 아프다. 이어받지 말아야 할 때 이어받으면 사용자는 엉뚱한 역에서 내리고,
 * 이어받아야 할 때 버리면 탑승 중 재탐색을 수락한 여정은 <b>남은 내내 "확인 중"</b>이다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "journey.reroute.enabled=true"
})
@DisplayName("재탐색 계승 — 구간이 완전히 같을 때만")
class SubwayBindingRerouteInheritanceIT {

    private static final String SESSION_ID = "sess-subway-binding-inherit-it";
    private static final String PATH_KEY = "0";
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 14);
    private static final String BOARD_STATION = "0222";
    private static final String ALIGHT_STATION = "0240";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired AcceptRerouteProposalUseCase acceptUseCase;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean PredictTransferUseCase predictTransferUseCase;
    @MockBean OdSayApiPort odSayApiPort;
    @MockBean TransferStopResolver transferStopResolver;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private JourneySnapshot journey;
    private Long oldRideLegId;
    private int rideLegIndex;

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
        oldRideLegId = rideLegIdOf(journey.routeRevision());
        rideLegIndex = legIndexOf(oldRideLegId);
        // 역 ID 해석은 정적 데이터가 있어야 한다(여기서는 꺼져 있다). 계승 판정이 보는 값이라
        // 실제 여정에서 채워졌을 값을 직접 넣는다.
        jdbcTemplate.update("""
                update journey_leg set start_stop_id = ?, end_stop_id = ?
                 where id = ?
                """, BOARD_STATION, ALIGHT_STATION, oldRideLegId);
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(this::deleteBindingsOf);
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    // ── 이어받는다 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("구간이 완전히 같고 확정이면 새 개정본에 행이 새로 생긴다")
    void inheritsConfirmedWhenSegmentIsIdentical() {
        insertBinding("CONFIRMED", "1001", 1);

        acceptRemainingPathFrom(busWaitLegIndex());

        Long newLegId = rideLegIdOf(journey.routeRevision() + 1);
        assertThat(newLegId).isNotEqualTo(oldRideLegId);
        assertThat(statusOf(newLegId)).isEqualTo("CONFIRMED");
        assertThat(trainNoOf(newLegId)).isEqualTo("1001");
        assertThat(sourceOf(newLegId)).isEqualTo("INHERITED_ON_REROUTE");
        assertThat(versionOf(newLegId)).isZero();
        assertThat(auditTypesOf(newLegId)).contains("INHERITED_ON_REROUTE");
        // 확정은 옛 개정본에서도 접지 않는다 — canonical 사실이다.
        assertThat(statusOf(oldRideLegId)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("잠정(자동 판정기 시절 행)도 이어받는다 — 링이 잠정 열차로도 돈다. 근거 감사행은 재발행하지 않는다")
    void inheritsProvisionalWithoutReissuingEvidence() {
        insertBinding("PROVISIONAL", "1001", 1);
        insertEvidence(oldRideLegId, "EVIDENCE", "MOVEMENT_START", 1, 0, null);
        insertEvidence(oldRideLegId, "DECISION", "PROVISIONAL", null, 0, "9001");

        acceptRemainingPathFrom(busWaitLegIndex());

        Long newLegId = rideLegIdOf(journey.routeRevision() + 1);
        assertThat(statusOf(newLegId)).isEqualTo("PROVISIONAL");
        assertThat(trainNoOf(newLegId)).isEqualTo("1001");
        assertThat(movementSeqOf(newLegId)).isEqualTo(1);
        // 판정기가 없어 근거를 옮길 이유가 없다 — 계승 사실 한 줄만 남는다.
        assertThat(auditTypesOf(newLegId)).containsExactly("INHERITED_ON_REROUTE");
        // 옛 개정본 행은 접히되 열차 identity 는 남는다.
        assertThat(statusOf(oldRideLegId)).isEqualTo("ABANDONED");
        assertThat(trainNoOf(oldRideLegId)).isEqualTo("1001");
    }

    // ── 이어받지 않는다 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("그 지하철 구간 자체가 교체되면 새 단계에는 고정이 없다")
    void doesNotInheritWhenTheSubwayLegIsReplaced() {
        insertBinding("PROVISIONAL", "1001", 1);

        acceptRemainingPathFrom(0);

        assertThat(bindingCountOf(journey.routeRevision() + 1)).isZero();
        assertThat(statusOf(oldRideLegId)).isEqualTo("ABANDONED");
        assertThat(trainNoOf(oldRideLegId)).isEqualTo("1001");
    }

    @Test
    @DisplayName("아직 열차를 못 고른 PENDING 은 이어받지 않는다 — 새 관측이 오면 그때 열린다")
    void doesNotInheritPending() {
        insertBinding("PENDING", null, 1);

        acceptRemainingPathFrom(busWaitLegIndex());

        assertThat(bindingCountOf(journey.routeRevision() + 1)).isZero();
        assertThat(statusOf(oldRideLegId)).isEqualTo("ABANDONED");
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private void acceptRemainingPathFrom(int fromLegIndex) {
        String candidateId = UUID.randomUUID().toString();
        RerouteCandidate candidate = new RerouteCandidate(candidateId,
                watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind.REMAINING_PATH,
                new RerouteCandidateApply.RemainingPathApply(fromLegIndex,
                        fromLegIndex == 0 ? wholeReplacementPath() : tailPath()),
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
        Long proposalId = jdbcTemplate.queryForObject("""
                select id from reroute_proposal where journey_id = ? order by id desc limit 1
                """, Long.class, journey.journeyId());

        acceptUseCase.accept(userId, journey.journeyId(), proposalId, candidateId);
    }

    private void insertBinding(String status, String trainNo, Integer movementSeq) {
        jdbcTemplate.update("""
                insert into subway_leg_binding
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision, leg_index,
                     status, train_no, train_service_date, train_line_id, train_direction,
                     version, reset_generation, last_movement_seq)
                values (now(), now(), ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?)
                """, oldRideLegId, journey.journeyId(), journey.routeRevision(), rideLegIndex,
                status, trainNo,
                trainNo == null ? null : java.sql.Date.valueOf(SERVICE_DATE),
                trainNo == null ? null : "1002",
                trainNo == null ? null : "내선",
                movementSeq);
    }

    private void insertEvidence(Long legId, String kind, String type, Integer sequence,
                                Integer bindingVersion, String chosenTrainNo) {
        jdbcTemplate.update("""
                insert into subway_binding_event
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision, leg_index,
                     kind, event_type, evidence_sequence, algorithm_version, binding_version,
                     reset_generation, occurred_at, received_at,
                     chosen_train_no, chosen_service_date, chosen_line_id, chosen_direction, detail)
                values (now(), now(), ?, ?, ?, ?, ?, ?, ?, 'test', ?, 0, now(), now(),
                        ?, ?, ?, ?, ?::jsonb)
                """, legId, journey.journeyId(), journey.routeRevision(), rideLegIndex,
                kind, type, sequence, bindingVersion,
                chosenTrainNo,
                chosenTrainNo == null ? null : java.sql.Date.valueOf(SERVICE_DATE),
                chosenTrainNo == null ? null : "1002",
                chosenTrainNo == null ? null : "내선",
                "{\"movementStartedAt\":\"2026-08-14T00:00:00Z\"}");
    }

    // ── 조회 ────────────────────────────────────────────────────────────────

    private Long rideLegIdOf(int revision) {
        return jdbcTemplate.queryForObject("""
                select id from journey_leg
                 where journey_id = ? and route_revision = ? and type = 'RIDE_SUBWAY'
                   and deleted_at is null
                 order by leg_index limit 1
                """, Long.class, journey.journeyId(), revision);
    }

    private int legIndexOf(Long legId) {
        return jdbcTemplate.queryForObject(
                "select leg_index from journey_leg where id = ?", Integer.class, legId);
    }

    private int busWaitLegIndex() {
        return jdbcTemplate.queryForObject("""
                select min(leg_index) from journey_leg
                 where journey_id = ? and route_revision = ? and type = 'WAIT_BUS'
                   and deleted_at is null
                """, Integer.class, journey.journeyId(), journey.routeRevision());
    }

    private String statusOf(Long legId) {
        return jdbcTemplate.queryForObject(
                "select status from subway_leg_binding where journey_leg_id = ?",
                String.class, legId);
    }

    private String trainNoOf(Long legId) {
        return jdbcTemplate.queryForObject(
                "select train_no from subway_leg_binding where journey_leg_id = ?",
                String.class, legId);
    }

    private String sourceOf(Long legId) {
        return jdbcTemplate.queryForObject(
                "select source from subway_leg_binding where journey_leg_id = ?",
                String.class, legId);
    }

    private Integer versionOf(Long legId) {
        return jdbcTemplate.queryForObject(
                "select version from subway_leg_binding where journey_leg_id = ?",
                Integer.class, legId);
    }

    private Integer movementSeqOf(Long legId) {
        return jdbcTemplate.queryForObject(
                "select last_movement_seq from subway_leg_binding where journey_leg_id = ?",
                Integer.class, legId);
    }

    private Integer bindingCountOf(int revision) {
        return jdbcTemplate.queryForObject("""
                select count(*) from subway_leg_binding
                 where journey_id = ? and route_revision = ?
                """, Integer.class, journey.journeyId(), revision);
    }

    private List<String> auditTypesOf(Long legId) {
        return jdbcTemplate.queryForList(
                "select event_type from subway_binding_event where journey_leg_id = ?",
                String.class, legId);
    }

    // ── 경로 ────────────────────────────────────────────────────────────────

    /** 지하철 한 구간 → 도보 → 버스 한 구간. 환승은 버스 대기 단계로 들어간다. */
    private PathResult plannedPath() {
        return new PathResult(1,
                new PathInfo(60, 8000, 900, 1, "강남", "정류장Z", null),
                List.of(
                        SubPathFixture.subway(15).names("강남", "신촌")
                                .coords(127.00, 37.00, 127.10, 37.10)
                                .subwayStations(900222, 900240).wayCode(2)
                                .lanes(SubPathFixture.subwayLane("2호선", 2)).build(),
                        SubPathFixture.walk(3).names("신촌", "정류장G").distance(150.0)
                                .coords(127.10, 37.10, 127.20, 37.20).build(),
                        SubPathFixture.bus(40).names("정류장G", "정류장Z")
                                .coords(127.20, 37.20, 127.50, 37.50)
                                .busStops("ST-G", "ST-Z")
                                .lanes(SubPathFixture.busLane("721", "BL-721")).build()),
                List.of());
    }

    /** 지하철 구간은 그대로 두고 뒤만 갈아끼운다. */
    private PathResult tailPath() {
        return new PathResult(1,
                new PathInfo(25, 3800, 300, 0, "정류장G", "정류장Z", null),
                List.of(SubPathFixture.bus(15).names("정류장G", "정류장Z")
                        .coords(127.20, 37.20, 127.50, 37.50)
                        .busStops("ST-G", "ST-Z")
                        .lanes(SubPathFixture.busLane("360", "BL-360")).build()),
                List.of());
    }

    /** 지하철 구간 자체를 버스로 갈아끼운다. */
    private PathResult wholeReplacementPath() {
        return new PathResult(1,
                new PathInfo(50, 8000, 600, 0, "강남", "정류장Z", null),
                List.of(SubPathFixture.bus(45).names("강남", "정류장Z")
                        .coords(127.00, 37.00, 127.50, 37.50)
                        .busStops("ST-A", "ST-Z")
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

    private void deleteBindingsOf(Long id) {
        jdbcTemplate.update("""
                delete from subway_binding_event where journey_id in (
                    select id from journey_session where user_id = ?)
                """, id);
        jdbcTemplate.update("""
                delete from subway_leg_binding where journey_id in (
                    select id from journey_session where user_id = ?)
                """, id);
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
                "transfer_connection", "journey_event", "journey_notification")) {
            jdbcTemplate.update("delete from " + table
                    + " where journey_id in (select id from journey_session where user_id = ?)", id);
        }
        jdbcTemplate.update("delete from journey_session where user_id = ?", id);
    }
}
