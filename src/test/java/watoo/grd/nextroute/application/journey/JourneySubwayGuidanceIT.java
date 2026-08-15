package watoo.grd.nextroute.application.journey;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import watoo.grd.nextroute.application.journey.dto.JourneyEtaView;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.dto.SubwayGuidance;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPhase;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.journey.port.in.GetActiveJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.out.SubwayRingPort;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * 스냅샷에 실리는 지하철 안내의 계약.
 *
 * <p>여기서 막는 사고 넷 —
 * <ul>
 *   <li><b>{@code withEta()} 복제에서 안내가 사라진다</b>: 레코드를 통째로 다시 만드는 자리라
 *       필드를 더할 때마다 조용히 빠진다. 그러면 앱은 예측이 붙은 응답에서만 링을 잃는다</li>
 *   <li><b>미확정인데 다음 역이 실린다</b>: 앱은 그것을 실시간으로 읽는다</li>
 *   <li><b>상태와 신선도를 섞는다</b>: {@code CONFIRMED} 이면서 {@code STALE} 은 정상이다</li>
 *   <li><b>단계마다 고정을 조회한다</b>: 정확히 N+1 이고 지하철 여정일수록 나빠진다</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@DisplayName("여정 스냅샷의 지하철 안내 계약")
class JourneySubwayGuidanceIT {

    private static final String SESSION_ID = "sess-journey-subway-guidance-it";
    private static final String SHORT_PATH = "0";
    private static final String LONG_PATH = "1";
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 15);
    private static final Instant T0 = Instant.parse("2026-08-15T09:00:00Z");

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired GetActiveJourneyUseCase getActiveJourneyUseCase;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManagerFactory entityManagerFactory;

    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean SubwayRingPort ringPort;

    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, SHORT_PATH)).willReturn(stored(oneSubwayPath()));
        given(snapshotPort.find(SESSION_ID, LONG_PATH)).willReturn(stored(twoSubwayPath()));
        given(ringPort.find(anyLong(), anyInt(), anyInt(), anyInt())).willReturn(Optional.empty());
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(id -> jdbcTemplate.update("""
                delete from subway_leg_binding where journey_id in (
                    select id from journey_session where user_id = ?)
                """, id));
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    // ── 붙는 자리 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("고정이 없으면 안내가 없다 — 기존 필드는 그대로다(하위호환)")
    void withoutBindingThereIsNoGuidance() {
        Journey journey = start(SHORT_PATH);

        JourneySnapshot snapshot = snapshotOf(journey);

        assertThat(snapshot.subwayGuidance()).isNull();
        assertThat(snapshot.legs()).isNotEmpty();
        assertThat(snapshot.currentLeg()).isNotNull();
    }

    @Test
    @DisplayName("안내는 단계 안이 아니라 스냅샷 최상위에 있다")
    void guidanceLivesAtTheTop() {
        Journey journey = start(SHORT_PATH);
        insertBinding(journey, "PROVISIONAL");
        moveTo(journey, journey.rideLegIndex);

        JourneySnapshot snapshot = snapshotOf(journey);

        assertThat(snapshot.subwayGuidance()).isNotNull();
        assertThat(snapshot.subwayGuidance().alightingStationName()).isEqualTo("하차역");
    }

    @Test
    @DisplayName("withEta() 복제 뒤에도 안내가 남는다 — 여기서 빠지면 예측이 붙은 응답만 링을 잃는다")
    void guidanceSurvivesCloning() {
        Journey journey = start(SHORT_PATH);
        insertBinding(journey, "PROVISIONAL");
        moveTo(journey, journey.rideLegIndex);
        JourneySnapshot snapshot = snapshotOf(journey);
        SubwayGuidance before = snapshot.subwayGuidance();

        JourneySnapshot cloned = snapshot.withEta(
                new JourneyEtaView(null, null, null, null, null, T0), 15);

        assertThat(cloned.subwayGuidance()).isEqualTo(before);
    }

    // ── 확정 전후 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("미확정이면 다음 역도 진행률도 비어 있다")
    void unconfirmedCarriesNoRing() {
        Journey journey = start(SHORT_PATH);
        insertBinding(journey, "PROVISIONAL");
        moveTo(journey, journey.rideLegIndex);

        SubwayGuidance guidance = snapshotOf(journey).subwayGuidance();

        assertThat(guidance.bindingStatus()).isEqualTo(SubwayBindingStatus.PROVISIONAL);
        assertThat(guidance.nextStationName()).isNull();
        assertThat(guidance.segmentProgress()).isNull();
        assertThat(guidance.estimatedNextStationAt()).isNull();
        assertThat(guidance.quality()).isEqualTo(SubwayRingQuality.STALE);
    }

    @Test
    @DisplayName("확정이면 다음 역과 앵커가 실린다 — 상태와 신선도는 다른 축이다")
    void confirmedCarriesTheRing() {
        Journey journey = start(SHORT_PATH);
        insertBinding(journey, "CONFIRMED");
        moveTo(journey, journey.rideLegIndex);
        given(ringPort.find(journey.id, 1, journey.rideLegIndex, 0))
                .willReturn(Optional.of(ring(journey)));

        SubwayGuidance guidance = snapshotOf(journey).subwayGuidance();

        assertThat(guidance.bindingStatus()).isEqualTo(SubwayBindingStatus.CONFIRMED);
        assertThat(guidance.quality()).isEqualTo(SubwayRingQuality.NORMAL);
        assertThat(guidance.phase()).isEqualTo(SubwayRingPhase.RIDE_SUBWAY);
        assertThat(guidance.nextStationName()).isEqualTo("중간역");
        assertThat(guidance.remainingStations()).isEqualTo(2);
        assertThat(guidance.segmentProgress()).isEqualByComparingTo("0.45");
        assertThat(guidance.progressBaseAt()).isEqualTo(T0);
        assertThat(guidance.progressTarget()).isEqualByComparingTo("0.90");
        assertThat(guidance.estimatedNextStationAt()).isEqualTo(T0.plusSeconds(90));
        assertThat(guidance.progressValidUntil()).isEqualTo(T0.plusSeconds(120));
    }

    @Test
    @DisplayName("확정인데 링이 없으면 상태만 남고 값은 비운다 — 캐시 유실이 거짓 진행률이 되면 안 된다")
    void confirmedWithoutRingIsHonest() {
        Journey journey = start(SHORT_PATH);
        insertBinding(journey, "CONFIRMED");
        moveTo(journey, journey.rideLegIndex);

        SubwayGuidance guidance = snapshotOf(journey).subwayGuidance();

        assertThat(guidance.bindingStatus()).isEqualTo(SubwayBindingStatus.CONFIRMED);
        assertThat(guidance.quality()).isEqualTo(SubwayRingQuality.STALE);
        assertThat(guidance.segmentProgress()).isNull();
    }

    // ── 기다리는 단계 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("승강장에서 기다리는 중이면 짝 탑승 단계의 고정이 붙는다")
    void waitingLegBorrowsThePairedBinding() {
        Journey journey = start(SHORT_PATH);
        insertBinding(journey, "CONFIRMED");
        moveTo(journey, journey.rideLegIndex - 1);
        given(ringPort.find(journey.id, 1, journey.rideLegIndex, 0))
                .willReturn(Optional.of(ring(journey)));

        SubwayGuidance guidance = snapshotOf(journey).subwayGuidance();

        assertThat(guidance).isNotNull();
        assertThat(guidance.bindingStatus()).isEqualTo(SubwayBindingStatus.CONFIRMED);
        assertThat(guidance.nextStationName()).isEqualTo("중간역");
    }

    @Test
    @DisplayName("걷는 중이면 안내가 없다")
    void walkingLegHasNoGuidance() {
        Journey journey = start(SHORT_PATH);
        insertBinding(journey, "CONFIRMED");
        moveTo(journey, 0);

        assertThat(snapshotOf(journey).subwayGuidance()).isNull();
    }

    // ── N+1 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("단계가 늘어도 조립 질의 수가 늘지 않는다")
    void assemblyQueryCountDoesNotGrowWithLegs() {
        Journey shortJourney = start(SHORT_PATH);
        insertBinding(shortJourney, "CONFIRMED");
        moveTo(shortJourney, shortJourney.rideLegIndex);
        long shortCount = queriesFor(shortJourney);

        Journey longJourney = start(LONG_PATH);
        insertBinding(longJourney, "CONFIRMED");
        moveTo(longJourney, longJourney.rideLegIndex);
        long longCount = queriesFor(longJourney);

        assertThat(longJourney.legCount).isGreaterThan(shortJourney.legCount);
        assertThat(longCount).isEqualTo(shortCount);
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private long queriesFor(Journey journey) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        snapshotOf(journey);
        return statistics.getPrepareStatementCount();
    }

    private JourneySnapshot snapshotOf(Journey journey) {
        return getActiveJourneyUseCase.getActive(journey.userId).orElseThrow();
    }

    private SubwayRingState ring(Journey journey) {
        return new SubwayRingState(journey.id, 1, journey.rideLegIndex, 0,
                1, "1002000223", "중간역", "하차역", 2,
                new BigDecimal("0.4500"), T0, new BigDecimal("0.90"),
                T0.plusSeconds(90), T0.plusSeconds(120),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, T0, T0);
    }

    private Optional<RouteSearchSnapshotPort.StoredPath> stored(PathResult path) {
        return Optional.of(new RouteSearchSnapshotPort.StoredPath(
                null, path, Instant.now().plusSeconds(600)));
    }

    private Journey start(String pathKey) {
        Long userId = newUser();
        JourneySnapshot snapshot = startJourneyUseCase.start(userId,
                new StartJourneyCommand(SESSION_ID, pathKey, false));
        Long journeyId = snapshot.journeyId();
        Long rideLegId = jdbcTemplate.queryForObject("""
                select id from journey_leg
                 where journey_id = ? and type = 'RIDE_SUBWAY'
                 order by leg_index limit 1
                """, Long.class, journeyId);
        Integer rideLegIndex = jdbcTemplate.queryForObject(
                "select leg_index from journey_leg where id = ?", Integer.class, rideLegId);
        return new Journey(userId, journeyId, rideLegId, rideLegIndex, snapshot.legs().size());
    }

    /** 현재 단계를 옮긴다. 안내는 "지금 화면이 지하철인가"로만 붙는다. */
    private void moveTo(Journey journey, int legIndex) {
        jdbcTemplate.update("update journey_session set current_leg_index = ? where id = ?",
                legIndex, journey.id);
    }

    private void insertBinding(Journey journey, String status) {
        jdbcTemplate.update("""
                insert into subway_leg_binding
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision, leg_index,
                     status, train_no, train_service_date, train_line_id, train_direction,
                     version, reset_generation, last_movement_seq)
                values (now(), now(), ?, ?, 1, ?, ?, '2233', ?, '1002', '내선', 0, 0, 1)
                """, journey.rideLegId, journey.id, journey.rideLegIndex, status,
                java.sql.Date.valueOf(SERVICE_DATE));
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

    private PathResult oneSubwayPath() {
        return new PathResult(1, null,
                List.of(
                        SubPathFixture.walk(5).names("출발지", "승차역").distance(300.0).build(),
                        subwayLeg("승차역", "하차역", 900222, 900224, "중간역"),
                        SubPathFixture.walk(5).names("하차역", "목적지").distance(300.0).build()),
                List.of());
    }

    private PathResult twoSubwayPath() {
        return new PathResult(1, null,
                List.of(
                        SubPathFixture.walk(5).names("출발지", "승차역").distance(300.0).build(),
                        subwayLeg("승차역", "하차역", 900222, 900224, "중간역"),
                        SubPathFixture.walk(3).names("하차역", "환승역").distance(100.0).build(),
                        subwayLeg("환승역", "종착역", 900301, 900303, "환승중간역"),
                        SubPathFixture.walk(5).names("종착역", "목적지").distance(300.0).build()),
                List.of());
    }

    private watoo.grd.nextroute.application.route.dto.SubPathResult subwayLeg(
            String start, String end, int startId, int endId, String middle) {
        return SubPathFixture.subway(15).names(start, end)
                .subwayStations(startId, endId).wayCode(2)
                .lanes(SubPathFixture.subwayLane("2호선", 2))
                .stations(
                        new StationResult(0, String.valueOf(startId), start, null, null),
                        new StationResult(1, String.valueOf(startId + 1), middle, null, null),
                        new StationResult(2, String.valueOf(endId), end, null, null))
                .build();
    }

    private record Journey(Long userId, Long id, Long rideLegId, int rideLegIndex, int legCount) {
    }
}
