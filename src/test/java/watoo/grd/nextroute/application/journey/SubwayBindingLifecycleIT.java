package watoo.grd.nextroute.application.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.JourneyLeaseExpiryService;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 여정이 끝나는 <b>모든 길</b>에서 미완료 고정이 접히는지.
 *
 * <p>여기서 새면 그 고정은 영원히 남는다 — 이 표는 행을 지우지 않으므로 판정기가 끝난 여정의
 * {@code PENDING} 을 계속 재처리하고, 그 재처리는 스스로 멈추지 않는다.
 *
 * <p>동시에 두 가지를 <b>건드리지 않는지</b>도 본다. {@code CONFIRMED} 는 감사·복구용 사실이라
 * 접지 않고, 접을 때도 열차 identity 는 남긴다 — 잠정으로 무엇을 보고 있었는지가 사후 분석의
 * 재료다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("고정 수명주기 — 여정이 끝나는 네 길")
class SubwayBindingLifecycleIT {

    private static final String SESSION_ID = "sess-subway-binding-lifecycle-it";
    private static final String PATH_KEY = "0";
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 14);

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired ApplyJourneyEventUseCase applyJourneyEventUseCase;
    @Autowired JourneyLeaseExpiryService expiryService;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean RouteSearchSnapshotPort snapshotPort;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private JourneySnapshot journey;
    private Long rideLegId;

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, subwayPath(), Instant.now().plusSeconds(600))));
        userId = newUser();
        journey = startJourneyUseCase.start(userId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
        rideLegId = rideLegIdOf(journey.journeyId());
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(this::deleteBindingsOf);
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    // ── ① 사용자 종료 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("여정을 끝내면 미완료 고정이 접힌다 — 안 접으면 영원히 재처리된다")
    void endingTheJourneyAbandonsOpenBindings() {
        insertBinding(rideLegId, "PENDING", null);

        cancelJourney();

        assertThat(statusOf(rideLegId)).isEqualTo("ABANDONED");
        assertThat(versionOf(rideLegId)).isEqualTo(1);
    }

    @Test
    @DisplayName("접어도 열차 identity 는 남는다 — 계승 규칙과 모순되지 않아야 한다")
    void abandonKeepsTheTrainIdentity() {
        insertBinding(rideLegId, "PROVISIONAL", "1001");

        cancelJourney();

        assertThat(statusOf(rideLegId)).isEqualTo("ABANDONED");
        assertThat(trainNoOf(rideLegId)).isEqualTo("1001");
    }

    @Test
    @DisplayName("확정된 고정은 건드리지 않는다 — 감사·복구용 canonical 사실이다")
    void confirmedSurvivesTheEnd() {
        insertBinding(rideLegId, "CONFIRMED", "1001");

        cancelJourney();

        assertThat(statusOf(rideLegId)).isEqualTo("CONFIRMED");
        assertThat(versionOf(rideLegId)).isZero();
    }

    // ── ② lease 만료 배치 ───────────────────────────────────────────────────

    @Test
    @DisplayName("lease 만료 배치도 같은 일을 한다")
    void leaseExpiryAbandonsOpenBindings() {
        insertBinding(rideLegId, "PROVISIONAL", "1001");
        setLeaseAge(journey.journeyId(), "10 minutes");

        assertThat(expiryService.expireBatch()).contains(journey.journeyId());

        assertThat(statusOf(rideLegId)).isEqualTo("ABANDONED");
        assertThat(trainNoOf(rideLegId)).isEqualTo("1001");
    }

    // ── ③ 여정 갈아타기 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("새 여정으로 갈아타면 옛 여정의 고정이 정리된다 — 여기도 여정이 끝나는 길이다")
    void replacingTheJourneyAbandonsOpenBindings() {
        insertBinding(rideLegId, "PENDING", null);

        JourneySnapshot replaced = startJourneyUseCase.start(userId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, true));

        assertThat(replaced.journeyId()).isNotEqualTo(journey.journeyId());
        assertThat(statusOf(rideLegId)).isEqualTo("ABANDONED");
        // 새 여정의 단계에는 고정이 없다. 계승은 재탐색에서만 일어난다.
        assertThat(bindingCountOf(replaced.journeyId())).isZero();
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private void cancelJourney() {
        applyJourneyEventUseCase.apply(userId, journey.journeyId(),
                new JourneyCommand(UUID.randomUUID(), JourneyEventType.JOURNEY_CANCELED,
                        journey.commandVersion(), journey.routeRevision(), null, null,
                        Instant.now()));
    }

    private void setLeaseAge(Long journeyId, String interval) {
        jdbcTemplate.update("""
                update journey_session
                   set guidance_lease_until = now() - interval '%s',
                       last_heartbeat_at = now() - interval '%s'
                 where id = ?
                """.formatted(interval, interval), journeyId);
    }

    private Long rideLegIdOf(Long journeyId) {
        return jdbcTemplate.queryForObject("""
                select id from journey_leg
                 where journey_id = ? and type = 'RIDE_SUBWAY'
                 order by leg_index limit 1
                """, Long.class, journeyId);
    }

    private void insertBinding(Long legId, String status, String trainNo) {
        Integer legIndex = jdbcTemplate.queryForObject(
                "select leg_index from journey_leg where id = ?", Integer.class, legId);
        Long journeyId = jdbcTemplate.queryForObject(
                "select journey_id from journey_leg where id = ?", Long.class, legId);
        jdbcTemplate.update("""
                insert into subway_leg_binding
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision, leg_index,
                     status, train_no, train_service_date, train_line_id, train_direction,
                     version, reset_generation, last_movement_seq)
                values (now(), now(), ?, ?, 1, ?, ?, ?, ?, ?, ?, 0, 0, 1)
                """, legId, journeyId, legIndex, status, trainNo,
                trainNo == null ? null : java.sql.Date.valueOf(SERVICE_DATE),
                trainNo == null ? null : "1002",
                trainNo == null ? null : "내선");
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

    private Integer versionOf(Long legId) {
        return jdbcTemplate.queryForObject(
                "select version from subway_leg_binding where journey_leg_id = ?",
                Integer.class, legId);
    }

    private Integer bindingCountOf(Long journeyId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from subway_leg_binding where journey_id = ?",
                Integer.class, journeyId);
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
