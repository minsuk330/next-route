package watoo.grd.nextroute.application.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;

/**
 * 스키마가 스스로 지키는 것들.
 *
 * <p>여기 있는 규칙은 코드에도 있지만 <b>DB 에도</b> 있어야 한다. 판정기·배치·수동 수정처럼
 * 애플리케이션을 거치지 않는 쓰기 경로가 나중에 생기기 때문이다. 그때 마지막 방어선은 스키마다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("탑승 열차 고정 스키마가 잘못된 값을 막는다")
class SubwayBindingSchemaIT {

    private static final String SESSION_ID = "sess-subway-binding-schema-it";
    private static final String PATH_KEY = "0";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository bindingRepository;
    @MockBean RouteSearchSnapshotPort snapshotPort;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long journeyId;
    private Long rideLegId;

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
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from subway_binding_event where journey_id = ?", journeyId);
        jdbcTemplate.update("delete from subway_leg_binding where journey_id = ?", journeyId);
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    // ── 고정 표 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("한 단계에 고정은 하나뿐이다")
    void oneBindingPerLeg() {
        insertBinding("PENDING", null);

        assertThatThrownBy(() -> insertBinding("PENDING", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("열차를 가리키는 네 값은 함께 있거나 함께 없다")
    void trainIdentityIsAllOrNothing() {
        // 번호만 있고 운행일·노선·방향이 없으면 어느 열차도 못 가리키면서 가리키는 척한다.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into subway_leg_binding
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision,
                     leg_index, status, train_no, version, reset_generation)
                values (now(), now(), ?, ?, 1, 1, 'PROVISIONAL', '2390', 0, 0)
                """, rideLegId, journeyId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("열차를 골랐다는 상태라면 그 열차가 누구인지 반드시 있어야 한다")
    void boundStatusRequiresATrain() {
        assertThatThrownBy(() -> insertBinding("CONFIRMED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("정해진 상태 이름만 받는다")
    void rejectsUnknownStatus() {
        assertThatThrownBy(() -> insertBinding("BOARDED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("잠금 값과 보정 세대는 음수가 될 수 없다")
    void rejectsNegativeCounters() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into subway_leg_binding
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision,
                     leg_index, status, version, reset_generation)
                values (now(), now(), ?, ?, 1, 1, 'PENDING', -1, 0)
                """, rideLegId, journeyId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("열차를 모두 갖춘 확정은 들어간다")
    void acceptsFullyIdentifiedTrain() {
        assertThatCode(() -> insertBinding("CONFIRMED", "2390")).doesNotThrowAnyException();

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from subway_leg_binding where journey_id = ?",
                Integer.class, journeyId)).isEqualTo(1);
    }

    // ── 열차 종류(V72, subway-express PR-S4) ─────────────────────────────

    @Test
    @DisplayName("V72: 종류는 NORMAL·EXPRESS·UNKNOWN 만, 급행은 서비스 id 가 반드시 있다, null(미분류)은 받는다")
    void trainKindChecks() {
        insertBinding("CONFIRMED", "9600");

        assertThatThrownBy(() -> setKind("DIRECT", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> setKind("EXPRESS", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> setKind("EXPRESS", "1009-express")).doesNotThrowAnyException();
        assertThatCode(() -> setKind("NORMAL", null)).doesNotThrowAnyException();
        assertThatCode(() -> setKind("UNKNOWN", null)).doesNotThrowAnyException();
        assertThatCode(() -> setKind(null, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("V72 배포: 배포 전에 묶인 급행(종류 null)을 링이 한 번 분류해 적는다 — 세대 그대로, 두 번째는 0 행")
    void deployClassifiesExistingExpressBindingOnce() {
        // 배포 전 코드가 쓴 모양 그대로(새 칸 없이) — 9호선 급행 9600
        insertBinding("CONFIRMED", "9600");
        Long id = bindingId();
        var before = bindingRepository.findById(id).orElseThrow();
        assertThat(before.getTrainKind()).as("배포 전 행은 미분류").isNull();

        int first = bindingRepository.classifyIfUnclassified(id, before.getVersion(), "EXPRESS", "1009-express",
                "2026-10-21");
        int second = bindingRepository.classifyIfUnclassified(id, before.getVersion(), "NORMAL", null, null);
        int staleGeneration = bindingRepository.classifyIfUnclassified(id, before.getVersion() + 1, "NORMAL", null,
                null);

        assertThat(first).isEqualTo(1);
        assertThat(second).as("이미 분류됨").isZero();
        assertThat(staleGeneration).isZero();
        var after = bindingRepository.findById(id).orElseThrow();
        assertThat(after.getTrainKind()).isEqualTo(watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind.EXPRESS);
        assertThat(after.getTrainServiceId()).isEqualTo("1009-express");
        assertThat(after.getCatalogVersion()).isEqualTo("2026-10-21");
        assertThat(after.getVersion()).as("링 키(세대)는 그대로").isEqualTo(before.getVersion());
    }

    @Test
    @DisplayName("V72 롤백: 칼럼만 남은 채 구버전 코드가 새 칸 없이 쓰고 읽어도 된다(전부 nullable, 기존 CHECK 무변경)")
    void rollbackOldCodeStillWritesAndReads() {
        insertBinding("CONFIRMED", "9600");
        setKind("EXPRESS", "1009-express");

        // 구버전의 갱신(새 칸을 모르는 UPDATE)과 조회(옛 칼럼 목록)
        assertThatCode(() -> jdbcTemplate.update("""
                update subway_leg_binding set status = 'CONFIRMED', train_no = '9602', version = version + 1,
                       updated_at = now() where journey_id = ?
                """, journeyId)).doesNotThrowAnyException();
        assertThat(jdbcTemplate.queryForObject("""
                select train_no from subway_leg_binding where journey_id = ?
                """, String.class, journeyId)).isEqualTo("9602");
        // 다른 단계에 구버전이 새로 넣는 행도 들어간다
        jdbcTemplate.update("delete from subway_leg_binding where journey_id = ?", journeyId);
        assertThatCode(() -> insertBinding("CONFIRMED", "9100")).doesNotThrowAnyException();
    }

    private void setKind(String kind, String serviceId) {
        jdbcTemplate.update("""
                update subway_leg_binding set train_kind = ?, train_service_id = ?, train_line_id = '1009'
                 where journey_id = ?
                """, kind, serviceId, journeyId);
    }

    private Long bindingId() {
        return jdbcTemplate.queryForObject("select id from subway_leg_binding where journey_id = ?", Long.class,
                journeyId);
    }

    // ── 감사 표 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("이동 시작과 정차는 순번을 따로 센다 — 서로의 1 번이 충돌하지 않는다")
    void movementAndEpisodeShareTheSameSequenceSpace() {
        insertEvidence("MOVEMENT_START", 1);

        assertThatCode(() -> insertEvidence("STATION_STOP_EPISODE", 1))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("같은 종류의 같은 순번은 한 번만 들어간다")
    void duplicateEvidenceIsRejected() {
        insertEvidence("MOVEMENT_START", 1);

        assertThatThrownBy(() -> insertEvidence("MOVEMENT_START", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("관측에는 순번이 반드시 있어야 한다 — 없으면 같은 것이 무한히 쌓인다")
    void evidenceRequiresASequence() {
        assertThatThrownBy(() -> insertEvidence("MOVEMENT_START", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("판단 행에 관측 이름을 넣을 수 없다")
    void decisionCannotCarryAnEvidenceName() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into subway_binding_event
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision, leg_index,
                     kind, event_type, reset_generation, occurred_at, received_at)
                values (now(), now(), ?, ?, 1, 1, 'DECISION', 'MOVEMENT_START', 0, now(), now())
                """, rideLegId, journeyId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("고른 열차도 네 값이 함께 있거나 함께 없다")
    void chosenTrainIdentityIsAllOrNothing() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into subway_binding_event
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision, leg_index,
                     kind, event_type, reset_generation, occurred_at, received_at, chosen_train_no)
                values (now(), now(), ?, ?, 1, 1, 'DECISION', 'PROVISIONAL', 0, now(), now(), '2390')
                """, rideLegId, journeyId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("없는 단계를 가리키는 감사행은 만들 수 없다")
    void auditRowMustPointAtARealLeg() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into subway_binding_event
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision, leg_index,
                     kind, event_type, evidence_sequence, reset_generation, occurred_at, received_at)
                values (now(), now(), -1, ?, 1, 1, 'EVIDENCE', 'MOVEMENT_START', 1, 0, now(), now())
                """, journeyId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── 도우미 ──────────────────────────────────────────────────────────

    private void insertBinding(String status, String trainNo) {
        jdbcTemplate.update("""
                insert into subway_leg_binding
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision, leg_index,
                     status, train_no, train_service_date, train_line_id, train_direction,
                     version, reset_generation)
                values (now(), now(), ?, ?, 1, 1, ?, ?, ?, ?, ?, 0, 0)
                """, rideLegId, journeyId, status,
                trainNo, trainNo == null ? null : java.sql.Date.valueOf("2026-08-13"),
                trainNo == null ? null : "1002", trainNo == null ? null : "내선");
    }

    private void insertEvidence(String eventType, Integer sequence) {
        jdbcTemplate.update("""
                insert into subway_binding_event
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision, leg_index,
                     kind, event_type, evidence_sequence, reset_generation, occurred_at, received_at)
                values (now(), now(), ?, ?, 1, 1, 'EVIDENCE', ?, ?, 0, now(), now())
                """, rideLegId, journeyId, eventType, sequence);
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
