package watoo.grd.nextroute.application.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingCandidate;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingDecision;
import watoo.grd.nextroute.application.journey.dto.SubwayBoardingEvidence;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.in.SubmitSubwayBoardingEvidenceUseCase;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.journey.service.SubwayBindingMatchTxService;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingConfidence;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingEventType;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 두 트랜잭션이 <b>같은 고정 행</b>에서 실제로 만났을 때 무슨 일이 일어나는지.
 *
 * <h2>왜 단위 시험으로 안 되는가</h2>
 *
 * <p>여기서 막으려는 사고는 예외를 내지 않는다. {@code SubwayLegBinding} 에는 {@code @Version} 도
 * {@code @DynamicUpdate} 도 없어서, 더티체킹 UPDATE 가 매핑 칼럼 <b>전부</b>를 자기가 읽었던 낡은
 * 스냅샷으로 덮는다. 잠금이 빠지면 판정이 통째로 사라지는데 로그에도 지표에도 아무것도 안 남는다.
 * 그 조용함은 진짜 두 커넥션이 붙어야만 재현된다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "journey.subway.binding.lock-timeout=PT1S"
})
@DisplayName("고정 행을 두고 판정기와 관측이 만났을 때")
class SubwayBindingConcurrencyIT {

    private static final String SESSION_ID = "sess-subway-binding-concurrency-it";
    private static final String PATH_KEY = "0";
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 14);

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired SubmitSubwayBoardingEvidenceUseCase submitUseCase;
    @Autowired SubwayLegBindingRepository bindingRepository;
    @Autowired JourneySessionRepository sessionRepository;
    @Autowired SubwayBindingMatchTxService txService;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean RouteSearchSnapshotPort snapshotPort;

    private final List<Long> createdUserIds = new ArrayList<>();
    private ExecutorService executor;
    private Long userId;
    private Long journeyId;
    private Long rideLegId;
    private int rideLegIndex;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, subwayPath(), Instant.now().plusSeconds(600))));
        userId = newUser();
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
        insertPendingBinding();
    }

    @AfterEach
    void cleanUp() {
        executor.shutdownNow();
        jdbcTemplate.update("delete from subway_binding_event where journey_id = ?", journeyId);
        jdbcTemplate.update("delete from subway_leg_binding where journey_id = ?", journeyId);
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    @Test
    @DisplayName("관측이 행을 잡고 있으면 판정기는 기다리지 않고 건너뛴다")
    void matcherSkipsInsteadOfWaiting() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        SubwayLegBinding expected = bindingRepository.findByJourneyLegId(rideLegId).orElseThrow();

        Future<?> holder = executor.submit(() -> transactionTemplate.execute(status -> {
            bindingRepository.findForUpdate(rideLegId);
            held.countDown();
            sleep(800);
            return null;
        }));
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

        long startedAt = System.nanoTime();
        boolean applied = txService.apply(expected, provisionalDecision());
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(applied).isFalse();
        // 기다렸다면 800ms 를 넘겼을 것이다. 건너뛰었으므로 그 한참 아래에서 끝난다.
        assertThat(elapsedMs).isLessThan(500);
        assertThat(statusOf()).isEqualTo("PENDING");
        holder.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("판정이 먼저 커밋되면 뒤이은 관측이 그것을 덮지 않는다 — 순번만 얹힌다")
    void observationDoesNotClobberACommittedDecision() throws Exception {
        CountDownLatch decided = new CountDownLatch(1);

        Future<?> matcher = executor.submit(() -> transactionTemplate.execute(status -> {
            sessionRepository.lockActiveRevisionForShare(journeyId, 1);
            bindingRepository.findForMatch(rideLegId);
            bindingRepository.casBind(rideLegId, "PROVISIONAL", "2002", SERVICE_DATE,
                    "1002", "내선", 0, 0, 1, null, LocalDateTime.now());
            decided.countDown();
            // 커밋 전에 관측이 도착하게 만든다. 잠금이 없으면 여기서 낡은 스냅샷이 만들어진다.
            sleep(400);
            return null;
        }));
        assertThat(decided.await(5, TimeUnit.SECONDS)).isTrue();

        submitUseCase.submit(userId, journeyId, 1, rideLegIndex, movementStart(2));
        matcher.get(5, TimeUnit.SECONDS);

        assertThat(statusOf()).isEqualTo("PROVISIONAL");
        assertThat(trainNo()).isEqualTo("2002");
        assertThat(versionOf()).isEqualTo(1);
        assertThat(movementSeq()).isEqualTo(2);
    }

    @Test
    @DisplayName("잠금이 오래 풀리지 않으면 관측은 재시도 가능한 실패로 끝난다 — 무한정 매달리지 않는다")
    void observationFailsFastWhenTheRowIsHeldTooLong() throws Exception {
        CountDownLatch held = new CountDownLatch(1);

        Future<?> holder = executor.submit(() -> transactionTemplate.execute(status -> {
            bindingRepository.findForUpdate(rideLegId);
            held.countDown();
            sleep(3_000);
            return null;
        }));
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() ->
                submitUseCase.submit(userId, journeyId, 1, rideLegIndex, movementStart(2)))
                .isInstanceOf(PessimisticLockingFailureException.class);

        holder.get(10, TimeUnit.SECONDS);
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private SubwayBoardingEvidence movementStart(int sequence) {
        Instant now = Instant.now();
        return new SubwayBoardingEvidence(SubwayBindingEventType.MOVEMENT_START, sequence,
                now.minusSeconds(20), null, null, "STATIONARY", "AUTOMOTIVE",
                SubwayBindingConfidence.HIGH, 20, true, now.minusSeconds(5));
    }

    private SubwayBindingDecision provisionalDecision() {
        SubwayBindingCandidate candidate = new SubwayBindingCandidate("2002", SERVICE_DATE,
                "1002", "내선", Instant.now().minusSeconds(60), Instant.now().minusSeconds(50),
                SubwayTrainEvent.Quality.OBSERVED, null, SubwayExpressType.NORMAL, 0);
        return SubwayBindingDecision.provisional(candidate, List.of(candidate), "t0-match");
    }

    private void insertPendingBinding() {
        jdbcTemplate.update("""
                insert into subway_leg_binding
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision, leg_index,
                     status, version, reset_generation, last_movement_seq, boarded_detected_at)
                values (now(), now(), ?, ?, 1, ?, 'PENDING', 0, 0, 1, now())
                """, rideLegId, journeyId, rideLegIndex);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String statusOf() {
        return jdbcTemplate.queryForObject(
                "select status from subway_leg_binding where journey_leg_id = ?",
                String.class, rideLegId);
    }

    private String trainNo() {
        return jdbcTemplate.queryForObject(
                "select train_no from subway_leg_binding where journey_leg_id = ?",
                String.class, rideLegId);
    }

    private Integer versionOf() {
        return jdbcTemplate.queryForObject(
                "select version from subway_leg_binding where journey_leg_id = ?",
                Integer.class, rideLegId);
    }

    private Integer movementSeq() {
        return jdbcTemplate.queryForObject(
                "select last_movement_seq from subway_leg_binding where journey_leg_id = ?",
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
