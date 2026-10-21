package watoo.grd.nextroute.application.journey;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import watoo.grd.nextroute.application.journey.dto.JourneyLegView;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.exception.ActiveJourneyExistsException;
import watoo.grd.nextroute.application.journey.port.in.JourneyQueryUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 여정 시작을 실제 DB 로 확인한다.
 *
 * <p>단위 테스트로는 여기까지 못 온다. "사람당 진행 중인 여정 하나"는 잠금과 부분 유일 인덱스가
 * 함께 지키는 규칙이고, 둘이 맞물리는 모습은 실제 트랜잭션 두 개가 부딪혀야 드러난다.
 *
 * <p>트랜잭션을 걸지 않는다. 테스트가 트랜잭션을 열면 두 스레드가 서로의 커밋을 볼 수 없어
 * 검증하려는 충돌 자체가 일어나지 않는다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
class JourneyStartIT {

    private static final String SESSION_ID = "sess-it";
    private static final String PATH_KEY = "0";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired JourneyQueryUseCase getActiveJourneyUseCase;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;

    @MockBean RouteSearchSnapshotPort snapshotPort;

    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        givenSnapshotOwnedBy(null, twoBusPath());
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    private void deleteJourneysOf(Long userId) {
        // 단계가 후보를 가리키는 FK 를 먼저 끊는다. 순환 참조라 이 순서가 아니면 지울 수 없다.
        jdbcTemplate.update("""
                update journey_leg set selected_route_option_id = null
                where journey_id in (select id from journey_session where user_id = ?)
                """, userId);
        jdbcTemplate.update("""
                delete from journey_leg_route_option
                where journey_leg_id in (
                    select l.id from journey_leg l
                    join journey_session s on s.id = l.journey_id
                    where s.user_id = ?)
                """, userId);
        for (String table : List.of("journey_leg", "journey_revision",
                "transfer_connection", "journey_event")) {
            jdbcTemplate.update(
                    "delete from " + table
                            + " where journey_id in (select id from journey_session where user_id = ?)",
                    userId);
        }
        // 알림 outbox 는 여정을 FK 로 잡고 있다. 평가가 위험을 감지하면 여기에도 행이 생기므로
        // 여정보다 먼저 지운다.
        jdbcTemplate.update("""
                delete from journey_notification where journey_id in (
                    select id from journey_session where user_id = ?)
                """, userId);
        jdbcTemplate.update("delete from journey_session where user_id = ?", userId);
    }

    private Long newUser() {
        Long id = jdbcTemplate.queryForObject("""
                insert into users (created_at, updated_at, token_generation)
                values (now(), now(), 0) returning id
                """, Long.class);
        createdUserIds.add(id);
        return id;
    }

    /** 버스 → 도보 → 버스. 환승 하나, 두 번째 구간은 노선 후보가 둘이다. */
    private PathResult twoBusPath() {
        return new PathResult(1,
                new PathInfo(40, 2500, 600, 1, "정류장A", "정류장Z", null),
                List.of(
                        SubPathFixture.bus(20).names("정류장A", "정류장F")
                                .busStops("ST-A", "ST-F")
                                .lanes(SubPathFixture.busLane("370", "BL-370")).build(),
                        SubPathFixture.walk(3).names("정류장F", "정류장G").distance(150.0).build(),
                        SubPathFixture.bus(15).names("정류장G", "정류장Z")
                                .busStops("ST-G", "ST-Z")
                                .lanes(SubPathFixture.busLane("721", "BL-721"),
                                        SubPathFixture.busLane("140", "BL-140")).build()),
                List.of());
    }

    private void givenSnapshotOwnedBy(Long ownerUserId, PathResult path) {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        ownerUserId, path, Instant.now().plusSeconds(600))));
    }

    private JourneySnapshot start(Long userId, boolean replace) {
        return startJourneyUseCase.start(userId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, replace));
    }

    private int countSessions(Long userId, JourneyStatus status) {
        return jdbcTemplate.queryForObject(
                "select count(*) from journey_session where user_id = ? and status = ?",
                Integer.class, userId, status.name());
    }

    // ── 저장 결과 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("검색 스냅샷의 급행 끄기가 여정 세션에 저장된다 — 칸 없는 옛 스냅샷은 false(subway-express S5)")
    void start_persistsExcludeExpress() {
        Long userId = newUser();
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(null, twoBusPath(), Instant.now().plusSeconds(600), true)));

        JourneySnapshot on = start(userId, false);

        assertThat(jdbcTemplate.queryForObject("select exclude_express from journey_session where id = ?",
                Boolean.class, on.journeyId())).isTrue();

        givenSnapshotOwnedBy(null, twoBusPath());
        JourneySnapshot off = start(userId, true);

        assertThat(jdbcTemplate.queryForObject("select exclude_express from journey_session where id = ?",
                Boolean.class, off.journeyId())).isFalse();
    }

    @Test
    @DisplayName("시작하면 단계와 환승이 함께 저장되고 첫 단계만 진행 중이다")
    void start_persistsLegsAndTransfers() {
        Long userId = newUser();

        JourneySnapshot snapshot = start(userId, false);

        assertThat(snapshot.status()).isEqualTo(JourneyStatus.ACTIVE);
        assertThat(snapshot.commandVersion()).isEqualTo(1L);
        assertThat(snapshot.evaluationVersion()).isZero();
        assertThat(snapshot.routeRevision()).isEqualTo(1);
        assertThat(snapshot.currentLegIndex()).isZero();
        assertThat(snapshot.guidanceLeaseUntil()).isAfter(snapshot.serverTime());

        assertThat(snapshot.legs()).extracting(JourneyLegView::type).containsExactly(
                JourneyLegType.WAIT_BUS, JourneyLegType.RIDE_BUS,
                JourneyLegType.TRANSFER_WALK,
                JourneyLegType.WAIT_BUS, JourneyLegType.RIDE_BUS);
        assertThat(snapshot.legs().get(0).state()).isEqualTo(JourneyLegState.ACTIVE);
        assertThat(snapshot.legs().stream().skip(1).map(JourneyLegView::state))
                .containsOnly(JourneyLegState.PENDING);

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from transfer_connection
                where journey_id = ?
                """, Integer.class, snapshot.journeyId())).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from journey_revision
                where journey_id = ? and status = 'ACTIVE' and route_revision = 1
                """, Integer.class, snapshot.journeyId())).isEqualTo(1);
    }

    @Test
    @DisplayName("첫 routeOption을 현재 노선으로 선택하고 나머지는 대안으로 보존한다")
    void routeOptionSelection() {
        Long userId = newUser();

        JourneySnapshot snapshot = start(userId, false);

        JourneyLegView singleWait = snapshot.legs().get(0);
        JourneyLegView singleRide = snapshot.legs().get(1);
        assertThat(singleWait.routeOptions()).hasSize(1);
        assertThat(singleWait.selectedRouteOptionId())
                .isNotNull()
                .isEqualTo(singleRide.selectedRouteOptionId());

        JourneyLegView multiWait = snapshot.legs().get(3);
        JourneyLegView multiRide = snapshot.legs().get(4);
        assertThat(multiWait.routeOptions())
                .extracting(o -> o.routeName())
                .containsExactly("721", "140");
        assertThat(multiWait.selectedRouteOptionId())
                .isEqualTo(multiWait.routeOptions().get(0).optionId());
        assertThat(multiRide.selectedRouteOptionId()).isEqualTo(multiWait.selectedRouteOptionId());
    }

    @Test
    @DisplayName("영속성 컨텍스트를 비워도 진행 중인 여정을 그대로 복원한다")
    void active_survivesDetach() {
        Long userId = newUser();
        JourneySnapshot started = start(userId, false);

        entityManager.clear();

        Optional<JourneySnapshot> restored = getActiveJourneyUseCase.getActive(userId);
        assertThat(restored).isPresent();
        assertThat(restored.get().journeyId()).isEqualTo(started.journeyId());
        assertThat(restored.get().legs()).hasSize(started.legs().size());
        assertThat(restored.get().legs().get(0).routeOptions()).hasSize(1);
    }

    @Test
    @DisplayName("진행 중인 여정이 없으면 비어 있다")
    void active_empty() {
        assertThat(getActiveJourneyUseCase.getActive(newUser())).isEmpty();
    }

    // ── 사람당 하나 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("진행 중인 여정이 있으면 거절하고 현재 여정을 알려 준다")
    void secondStart_rejected() {
        Long userId = newUser();
        JourneySnapshot first = start(userId, false);

        assertThatThrownBy(() -> start(userId, false))
                .isInstanceOf(ActiveJourneyExistsException.class)
                .satisfies(e -> assertThat(
                        ((ActiveJourneyExistsException) e).getCurrent().journeyId())
                        .isEqualTo(first.journeyId()));

        assertThat(countSessions(userId, JourneyStatus.ACTIVE)).isEqualTo(1);
    }

    @Test
    @DisplayName("replace=true 면 기존 여정을 SUPERSEDED 로 접고 새 여정을 만든다")
    void replace_endsPrevious() {
        Long userId = newUser();
        JourneySnapshot first = start(userId, false);

        JourneySnapshot second = start(userId, true);

        assertThat(second.journeyId()).isNotEqualTo(first.journeyId());
        assertThat(countSessions(userId, JourneyStatus.ACTIVE)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select end_reason from journey_session where id = ?
                """, String.class, first.journeyId())).isEqualTo("SUPERSEDED");
        assertThat(jdbcTemplate.queryForObject("""
                select status from journey_session where id = ?
                """, String.class, first.journeyId())).isEqualTo("CANCELED");
    }

    // ── 동시성 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("동시에 두 번 시작하면 하나만 살아남는다")
    void concurrentStart_onlyOneWins() throws Exception {
        Long userId = newUser();

        List<Outcome> outcomes = runConcurrently(
                () -> start(userId, false),
                () -> start(userId, false));

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.succeeded())
                .allSatisfy(o -> assertThat(o.error())
                        .as("동시 시작은 500 이 아니라 409 여야 한다")
                        .isInstanceOf(ActiveJourneyExistsException.class));
        assertThat(countSessions(userId, JourneyStatus.ACTIVE)).isEqualTo(1);
    }

    @Test
    @DisplayName("동시에 replace=true 로 시작해도 진행 중인 여정은 하나다")
    void concurrentReplace_stillOneActive() throws Exception {
        Long userId = newUser();
        start(userId, false);

        runConcurrently(() -> start(userId, true), () -> start(userId, true));

        assertThat(countSessions(userId, JourneyStatus.ACTIVE)).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 사용자끼리는 서로 방해하지 않는다")
    void differentUsers_bothSucceed() throws Exception {
        Long first = newUser();
        Long second = newUser();

        List<Outcome> outcomes = runConcurrently(
                () -> start(first, false),
                () -> start(second, false));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(countSessions(first, JourneyStatus.ACTIVE)).isEqualTo(1);
        assertThat(countSessions(second, JourneyStatus.ACTIVE)).isEqualTo(1);
    }

    /** 두 작업을 같은 순간에 출발시킨다. 먼저 도착한 쪽이 이기는지를 보기 위해서다. */
    private List<Outcome> runConcurrently(Callable<JourneySnapshot> first,
                                          Callable<JourneySnapshot> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<Outcome>> futures = List.of(
                    pool.submit(() -> await(gate, first)),
                    pool.submit(() -> await(gate, second)));
            gate.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(20, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private Outcome await(CountDownLatch gate, Callable<JourneySnapshot> task) {
        try {
            gate.await();
            return new Outcome(task.call(), null);
        } catch (Exception e) {
            return new Outcome(null, e);
        }
    }

    private record Outcome(JourneySnapshot snapshot, Exception error) {
        boolean succeeded() {
            return error == null;
        }
    }
}
