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
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.JourneyDueClaimService;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 평가 대상을 고르고 선점하는 규칙을 DB 까지 확인한다.
 *
 * <p>여기서 못박는 것은 "같은 여정을 두 번 평가하지 않는다"다. 두 번 평가하면 외부 API 호출이
 * 그만큼 낭비되고, 두 결과가 서로를 덮으며 어느 것이 최신인지 알 수 없게 된다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("평가 대상 선점")
class JourneyDueEvaluatorIT {

    private static final String SESSION_ID = "sess-due-evaluator-it";
    private static final String PATH_KEY = "0";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired ApplyJourneyEventUseCase applyJourneyEventUseCase;
    @Autowired JourneyDueClaimService claimService;
    @Autowired JourneyEvaluatorProperties properties;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean RouteSearchSnapshotPort snapshotPort;

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
    @DisplayName("여정을 시작하면 곧바로 평가 대상이 된다")
    void startedJourneyIsDue() {
        Long journeyId = startJourney();

        assertThat(nextEvaluationAt(journeyId)).isNotNull();
        assertThat(claimedIds()).contains(journeyId);
    }

    @Test
    @DisplayName("선점은 다음 평가 시각을 lease 만큼 미래로 밀어 둔다")
    void claimPushesNextEvaluation() {
        Long journeyId = startJourney();

        List<JourneyDueClaim> claims = claimService.claimDue();

        JourneyDueClaim mine = claims.stream()
                .filter(claim -> claim.journeyId().equals(journeyId))
                .findFirst()
                .orElseThrow();
        // 심어 둔 값과 돌려받은 값이 정확히 같아야 한다 — 그 값으로 소유권을 확인한다.
        assertThat(nextEvaluationAt(journeyId)).isEqualTo(mine.leaseUntil());
        assertThat(nextEvaluationAt(journeyId)).isAfter(Instant.now());
    }

    @Test
    @DisplayName("선점한 여정은 곧바로 다시 잡히지 않는다")
    void claimedJourneyIsNotPickedAgain() {
        Long journeyId = startJourney();
        claimService.claimDue();

        assertThat(claimedIds()).doesNotContain(journeyId);
    }

    @Test
    @DisplayName("선점 시각이 지나면 다시 잡힌다 — 워커가 죽어도 여정이 멈추지 않는다")
    void expiredLeaseIsReclaimed() {
        Long journeyId = startJourney();
        claimService.claimDue();

        // 워커가 죽어 결과를 못 보낸 상태를 만든다.
        jdbcTemplate.update("""
                update journey_session set next_evaluation_at = now() - interval '10 seconds'
                where id = ?
                """, journeyId);

        assertThat(claimedIds()).contains(journeyId);
    }

    @Test
    @DisplayName("끝난 여정은 평가 대상이 아니다")
    void endedJourneyIsNotDue() {
        Long journeyId = startJourney();
        jdbcTemplate.update("""
                update journey_session
                set status = 'EXPIRED', end_reason = 'CLIENT_LEASE_EXPIRED',
                    ended_at = now(), next_evaluation_at = null
                where id = ?
                """, journeyId);

        assertThat(claimedIds()).doesNotContain(journeyId);
    }

    @Test
    @DisplayName("두 워커가 동시에 골라도 같은 여정을 두 번 잡지 않는다")
    void concurrentClaimsDoNotOverlap() throws Exception {
        List<Long> targets = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            targets.add(startJourney());
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<List<Long>> claim = () -> claimService.claimDue().stream()
                    .map(JourneyDueClaim::journeyId)
                    .toList();
            List<Future<List<Long>>> futures = pool.invokeAll(List.of(claim, claim));

            List<Long> first = futures.get(0).get();
            List<Long> second = futures.get(1).get();

            assertThat(Collections.disjoint(first, second)).isTrue();
            List<Long> claimed = new ArrayList<>(first);
            claimed.addAll(second);
            assertThat(claimed).containsAll(targets);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("한 번에 배치 크기보다 많이 잡지 않는다")
    void respectsBatchSize() {
        int batchSize = properties.getBatchSize();
        for (int i = 0; i < 3; i++) {
            startJourney();
        }

        assertThat(claimService.claimDue()).hasSizeLessThanOrEqualTo(batchSize);
    }

    // ── 선점 순서: 급한 환승 먼저 ─────────────────────────────────────────────
    //
    // 배치를 1로 줄여 한 번에 하나씩 잡는다. RETURNING 은 순서를 보장하지 않으므로 "어느 것이 먼저
    // 잡혔나"는 한 건씩 잡아서 본다.

    @Test
    @DisplayName("밀릴 때 급한 여정이 더 늦게 만기여도 먼저 잡힌다")
    void urgentJourneyIsClaimedFirst() {
        Long relaxed = startJourney();
        Long urgent = startJourney();
        dueSecondsAgo(relaxed, 30, false);
        dueSecondsAgo(urgent, 5, true);

        assertThat(claimOneAtATime(relaxed, urgent)).containsExactly(urgent, relaxed);
    }

    @Test
    @DisplayName("DUE_TIME 이면 예전처럼 예정 시각 순이다 — 되돌리는 경로")
    void dueTimeOrderWhenSwitchedOff() {
        Long relaxed = startJourney();
        Long urgent = startJourney();
        dueSecondsAgo(relaxed, 30, false);
        dueSecondsAgo(urgent, 5, true);

        JourneyEvaluatorProperties.ClaimOrder before = properties.getClaimOrder();
        properties.setClaimOrder(JourneyEvaluatorProperties.ClaimOrder.DUE_TIME);
        try {
            assertThat(claimOneAtATime(relaxed, urgent)).containsExactly(relaxed, urgent);
        } finally {
            properties.setClaimOrder(before);
        }
    }

    @Test
    @DisplayName("상한보다 오래 밀린 여정은 급한 여정 뒤, 덜 밀린 여정 앞에 선다")
    void starvedJourneyGoesBetweenUrgentAndRest() {
        Long urgent = startJourney();
        Long starved = startJourney();
        Long rest = startJourney();
        dueSecondsAgo(urgent, 5, true);
        dueSecondsAgo(starved, properties.getStarvationGuardSeconds() + 30, false);
        dueSecondsAgo(rest, properties.getStarvationGuardSeconds() - 30, false);

        assertThat(claimOneAtATime(urgent, starved, rest))
                .as("밀린 여정이 급한 여정을 앞지르면 계속 넘치는 동안 급한 환승이 다시 밀린다")
                .containsExactly(urgent, starved, rest);
    }

    @Test
    @DisplayName("단계가 바뀌면 그 여정은 급한 줄에 선다")
    void legTransitionMarksUrgent() {
        Long journeyId = startJourney();
        jdbcTemplate.update("update journey_session set evaluation_urgent = false where id = ?", journeyId);
        Long ownerId = jdbcTemplate.queryForObject(
                "select user_id from journey_session where id = ?", Long.class, journeyId);

        var outcome = applyJourneyEventUseCase.apply(ownerId, journeyId,
                new JourneyCommand(UUID.randomUUID(), JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                        1L, 1, 0, 1, Instant.now()));

        assertThat(outcome.result().name()).isEqualTo("APPLIED");
        assertThat(evaluationUrgent(journeyId)).isTrue();
    }

    /** 대상 여정만 남을 때까지 한 건씩 잡아 잡힌 순서를 돌려준다. */
    private List<Long> claimOneAtATime(Long... targets) {
        List<Long> wanted = List.of(targets);
        List<Long> order = new ArrayList<>();
        int batchBefore = properties.getBatchSize();
        properties.setBatchSize(1);
        try {
            // 다른 시험이 남긴 여정이 끼어 있어도 대상만 골라 순서를 본다. 상한을 둬 무한 반복을 막는다.
            for (int i = 0; i < 20 && order.size() < wanted.size(); i++) {
                List<JourneyDueClaim> claimed = claimService.claimDue();
                if (claimed.isEmpty()) {
                    break;
                }
                claimed.stream().map(JourneyDueClaim::journeyId).filter(wanted::contains).forEach(order::add);
            }
        } finally {
            properties.setBatchSize(batchBefore);
        }
        return order;
    }

    private void dueSecondsAgo(Long journeyId, long seconds, boolean urgent) {
        jdbcTemplate.update("""
                update journey_session
                   set next_evaluation_at = now() - make_interval(secs => ?), evaluation_urgent = ?
                 where id = ?
                """, seconds, urgent, journeyId);
    }

    private boolean evaluationUrgent(Long journeyId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select evaluation_urgent from journey_session where id = ?", Boolean.class, journeyId));
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private List<Long> claimedIds() {
        return claimService.claimDue().stream().map(JourneyDueClaim::journeyId).toList();
    }

    private Long startJourney() {
        Long ownerId = newUser();
        JourneySnapshot journey = startJourneyUseCase.start(ownerId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
        return journey.journeyId();
    }

    private Instant nextEvaluationAt(Long journeyId) {
        return jdbcTemplate.queryForObject(
                "select next_evaluation_at from journey_session where id = ?",
                Instant.class, journeyId);
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
        // 알림 outbox 는 여정을 FK 로 잡고 있다. 평가가 위험을 감지하면 여기에도 행이 생기므로
        // 여정보다 먼저 지운다.
        jdbcTemplate.update("""
                delete from journey_notification where journey_id in (
                    select id from journey_session where user_id = ?)
                """, id);
        jdbcTemplate.update("delete from journey_session where user_id = ?", id);
    }

    private PathResult busPath() {
        return new PathResult(1,
                new PathInfo(40, 2500, 600, 1, "정류장A", "정류장Z", null),
                List.of(
                        SubPathFixture.bus(20).names("정류장A", "정류장F")
                                .busStops("ST-A", "ST-F")
                                .lanes(SubPathFixture.busLane("370", "BL-370")).build(),
                        SubPathFixture.walk(3).names("정류장F", "정류장G").distance(150.0).build(),
                        SubPathFixture.bus(15).names("정류장G", "정류장Z")
                                .busStops("ST-G", "ST-Z")
                                .lanes(SubPathFixture.busLane("721", "BL-721")).build()),
                List.of());
    }
}
