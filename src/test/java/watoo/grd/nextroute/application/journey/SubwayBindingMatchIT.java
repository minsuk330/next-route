package watoo.grd.nextroute.application.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.TestPropertySource;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingCandidate;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingDecision;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.journey.service.SubwayBindingMatchTxService;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey.SubwayBindingMatchScheduler;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 판정기가 <b>무엇을 보고 무엇을 안 보는지</b>를 실제 DB 로 고정한다.
 *
 * <p>이 PR 최대의 사고 경로가 스캔 조건이다. 세션 상태·개정본 조건을 빠뜨리면 끝난 여정과 옛
 * 개정본의 {@code PENDING} 을 <b>영원히</b> 재처리한다 — 이 표는 행을 지우지 않으므로 그
 * 재처리는 스스로 멈추지 않는다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("탑승 열차 판정기의 스캔·반영 계약")
class SubwayBindingMatchIT {

    private static final String SESSION_ID = "sess-subway-binding-match-it";
    private static final String PATH_KEY = "0";
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 14);

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired SubwayLegBindingRepository bindingRepository;
    @Autowired SubwayBindingMatchTxService txService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ApplicationContext applicationContext;
    @Autowired TransactionTemplate transactionTemplate;
    @MockBean RouteSearchSnapshotPort snapshotPort;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long journeyId;
    private Long rideLegId;
    private int rideLegIndex;

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, subwayPath(), Instant.now().plusSeconds(600))));
        Long userId = newUser();
        JourneySnapshot journey = startJourneyUseCase.start(userId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
        journeyId = journey.journeyId();
        rideLegId = jdbcTemplate.queryForObject("""
                select id from journey_leg
                 where journey_id = ? and type = 'RIDE_SUBWAY'
                 order by leg_index limit 1
                """, Long.class, journeyId);
        rideLegIndex = jdbcTemplate.queryForObject(
                "select leg_index from journey_leg where id = ?", Integer.class, rideLegId);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from subway_binding_event where journey_id = ?", journeyId);
        jdbcTemplate.update("delete from subway_leg_binding where journey_id = ?", journeyId);
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    // ── 스캔 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("판정할 수 있는 미완료 고정만 훑는다")
    void scansOpenBindingsOfActiveJourneys() {
        insertBinding("PENDING", 1);

        assertThat(scanned()).containsExactly(rideLegId);
    }

    @Test
    @DisplayName("끝난 여정의 미완료 고정은 훑지 않는다 — 빠뜨리면 영원히 재처리된다")
    void skipsEndedJourneys() {
        insertBinding("PENDING", 1);
        jdbcTemplate.update(
                "update journey_session set status = 'COMPLETED', ended_at = now() where id = ?",
                journeyId);

        assertThat(scanned()).isEmpty();
    }

    @Test
    @DisplayName("옛 개정본의 미완료 고정은 훑지 않는다")
    void skipsSupersededRevisions() {
        insertBinding("PENDING", 1);
        jdbcTemplate.update("update journey_session set route_revision = 2 where id = ?", journeyId);

        assertThat(scanned()).isEmpty();
    }

    @Test
    @DisplayName("이동 시작 근거가 없는 PENDING 은 훑지 않는다 — 배치를 통째로 차지해 뒤 행이 굶는다")
    void skipsPendingWithoutMovementEvidence() {
        insertBinding("PENDING", null);

        assertThat(scanned()).isEmpty();
    }

    @Test
    @DisplayName("훑지 않는 그 행은 청소 질의가 따로 접는다 — 아니면 영원히 남는다")
    void sweepAbandonsPendingWithoutMovement() {
        insertBinding("PENDING", null);
        jdbcTemplate.update(
                "update subway_leg_binding set updated_at = now() - interval '1 hour' where journey_leg_id = ?",
                rideLegId);

        int swept = inTransaction(() -> bindingRepository.abandonStaleWithoutMovement(
                LocalDateTime.now(), LocalDateTime.now().minusMinutes(6)));

        assertThat(swept).isEqualTo(1);
        assertThat(statusOf(rideLegId)).isEqualTo("ABANDONED");
    }

    @Test
    @DisplayName("잠정 판정은 이동 시작 순번이 비어도 계속 본다 — 확정 기회를 잃으면 안 된다")
    void keepsScanningProvisionalWithoutMovementSequence() {
        insertBoundBinding("PROVISIONAL", "1001", null);

        assertThat(scanned()).containsExactly(rideLegId);
    }

    // ── CAS ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("확정은 판정 경로로 뒤집히지 않는다 — 되돌릴 수 있는 것은 사용자뿐이다")
    void confirmedIsNeverOverturnedByTheMatcher() {
        insertBoundBinding("CONFIRMED", "1001", 1);

        int rows = inTransaction(() -> bindingRepository.casBind(rideLegId, "PROVISIONAL", "2002",
                SERVICE_DATE, "1002", "내선", 0, 0, 1, null, LocalDateTime.now()));

        assertThat(rows).isZero();
        assertThat(trainNoOf(rideLegId)).isEqualTo("1001");
    }

    @Test
    @DisplayName("그 사이 관측 순번이 올라갔으면 CAS 는 0행이다 — 관측은 version 을 올리지 않는다")
    void casLosesWhenObservationSequenceMoved() {
        insertBinding("PENDING", 1);
        jdbcTemplate.update(
                "update subway_leg_binding set last_movement_seq = 2 where journey_leg_id = ?",
                rideLegId);

        int rows = inTransaction(() -> bindingRepository.casBind(rideLegId, "PROVISIONAL", "2002",
                SERVICE_DATE, "1002", "내선", 0, 0, 1, null, LocalDateTime.now()));

        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("순번이 그대로면 CAS 가 반영되고 version 이 오른다")
    void casAppliesWhenNothingMoved() {
        insertBinding("PENDING", 1);

        int rows = inTransaction(() -> bindingRepository.casBind(rideLegId, "PROVISIONAL", "2002",
                SERVICE_DATE, "1002", "내선", 0, 0, 1, null, LocalDateTime.now()));

        assertThat(rows).isEqualTo(1);
        assertThat(versionOf(rideLegId)).isEqualTo(1);
        assertThat(statusOf(rideLegId)).isEqualTo("PROVISIONAL");
    }

    @Test
    @DisplayName("접어도 열차 identity 는 남는다 — 무엇을 보고 있었는지가 사후 분석의 재료다")
    void abandonKeepsTheTrainIdentity() {
        insertBoundBinding("PROVISIONAL", "1001", 1);

        int rows = inTransaction(() ->
                bindingRepository.casAbandon(rideLegId, 0, 0, 1, null, LocalDateTime.now()));

        assertThat(rows).isEqualTo(1);
        assertThat(statusOf(rideLegId)).isEqualTo("ABANDONED");
        assertThat(trainNoOf(rideLegId)).isEqualTo("1001");
    }

    // ── 쓰기 트랜잭션 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("여정이 그 사이 끝났으면 아무것도 쓰지 않는다 — CAS 조건만으로는 못 막는다")
    void writeDoesNothingWhenSessionIsNoLongerActive() {
        insertBinding("PENDING", 1);
        SubwayLegBinding expected = bindingRepository.findByJourneyLegId(rideLegId).orElseThrow();
        jdbcTemplate.update(
                "update journey_session set status = 'COMPLETED', ended_at = now() where id = ?",
                journeyId);

        assertThat(txService.apply(expected, provisionalDecision())).isFalse();
        assertThat(statusOf(rideLegId)).isEqualTo("PENDING");
        assertThat(auditCount()).isZero();
    }

    @Test
    @DisplayName("반영되면 감사행의 binding_version 이 새 version 과 같다")
    void auditVersionMatchesTheRowAfterCas() {
        insertBinding("PENDING", 1);
        SubwayLegBinding expected = bindingRepository.findByJourneyLegId(rideLegId).orElseThrow();

        assertThat(txService.apply(expected, provisionalDecision())).isTrue();

        Integer auditVersion = jdbcTemplate.queryForObject("""
                select binding_version from subway_binding_event
                 where journey_leg_id = ? and kind = 'DECISION' order by id desc limit 1
                """, Integer.class, rideLegId);
        assertThat(auditVersion).isEqualTo(versionOf(rideLegId));
    }

    // ── 배포 스위치 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("기능이 꺼져 있으면 스케줄러 빈이 아예 없다")
    void schedulerIsAbsentWhenDisabled() {
        assertThat(applicationContext.getBeanNamesForType(SubwayBindingMatchScheduler.class))
                .isEmpty();
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    /** {@code @Modifying} 질의는 트랜잭션 안에서만 돈다. 운영에서는 부르는 서비스가 그 경계다. */
    private int inTransaction(java.util.function.IntSupplier action) {
        return transactionTemplate.execute(status -> action.getAsInt());
    }

    private List<Long> scanned() {
        return bindingRepository.findMatchable(50).stream()
                .map(SubwayLegBinding::getJourneyLegId)
                .toList();
    }

    private SubwayBindingDecision provisionalDecision() {
        SubwayBindingCandidate candidate = new SubwayBindingCandidate("2002", SERVICE_DATE,
                "1002", "내선", Instant.now().minusSeconds(60), Instant.now().minusSeconds(50),
                SubwayTrainEvent.Quality.OBSERVED, null, SubwayExpressType.NORMAL, 0);
        return SubwayBindingDecision.provisional(candidate, List.of(candidate), "t0-match");
    }

    private void insertBinding(String status, Integer movementSeq) {
        jdbcTemplate.update("""
                insert into subway_leg_binding
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision, leg_index,
                     status, version, reset_generation, last_movement_seq)
                values (now(), now(), ?, ?, 1, ?, ?, 0, 0, ?)
                """, rideLegId, journeyId, rideLegIndex, status, movementSeq);
    }

    private void insertBoundBinding(String status, String trainNo, Integer movementSeq) {
        jdbcTemplate.update("""
                insert into subway_leg_binding
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision, leg_index,
                     status, train_no, train_service_date, train_line_id, train_direction,
                     version, reset_generation, last_movement_seq)
                values (now(), now(), ?, ?, 1, ?, ?, ?, ?, '1002', '내선', 0, 0, ?)
                """, rideLegId, journeyId, rideLegIndex, status, trainNo,
                java.sql.Date.valueOf(SERVICE_DATE), movementSeq);
    }

    private String statusOf(Long legId) {
        return jdbcTemplate.queryForObject(
                "select status from subway_leg_binding where journey_leg_id = ?", String.class, legId);
    }

    private String trainNoOf(Long legId) {
        return jdbcTemplate.queryForObject(
                "select train_no from subway_leg_binding where journey_leg_id = ?", String.class, legId);
    }

    private Integer versionOf(Long legId) {
        return jdbcTemplate.queryForObject(
                "select version from subway_leg_binding where journey_leg_id = ?", Integer.class, legId);
    }

    private Integer auditCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from subway_binding_event where journey_leg_id = ? and kind = 'DECISION'",
                Integer.class, rideLegId);
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

    private PathResult subwayPath() {
        return new PathResult(1, null,
                List.of(
                        SubPathFixture.subway(15).names("강남", "신촌")
                                .subwayStations(900222, 900240).wayCode(2)
                                .lanes(SubPathFixture.subwayLane("2호선", 2)).build(),
                        SubPathFixture.walk(5).names("신촌", "목적지").distance(300.0).build()),
                List.of());
    }
}
