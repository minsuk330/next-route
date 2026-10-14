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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.reeval.ReevaluationProperties;
import watoo.grd.nextroute.application.journey.reeval.ReevaluationTier;
import watoo.grd.nextroute.application.journey.reeval.ReevaluationTrigger;
import watoo.grd.nextroute.application.journey.reeval.TriggerResult;
import watoo.grd.nextroute.application.journey.service.JourneyDueClaimService;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 이벤트 기반 재평가 트리거(#415-3)의 DB 계약.
 *
 * <p>핵심은 "처리 중이면 표시만, 아니면 앞당김"이다. 처리 중인 여정의 선점 증서를 덮으면 진행 중 계산이
 * 결과를 버리고, 이벤트가 계산 시간보다 잦은 여정은 영영 저장되지 않는다(livelock).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "journey.navigation.reeval.enabled=true",
        "journey.navigation.reeval.shadow=false",
        "journey.navigation.reeval.shed-backlog-threshold=0"
})
@DisplayName("재평가 트리거")
class JourneyReevaluationTriggerIT {

    private static final String SESSION_ID = "sess-reeval-trigger-it";
    private static final String PATH_KEY = "0";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired ApplyJourneyEventUseCase applyJourneyEventUseCase;
    @Autowired JourneyDueClaimService claimService;
    @Autowired JourneySessionRepository sessionRepository;
    @Autowired ReevaluationTrigger trigger;
    @Autowired ReevaluationProperties properties;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean RouteSearchSnapshotPort snapshotPort;

    private final List<Long> createdUserIds = new ArrayList<>();
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, busPath(), Instant.now().plusSeconds(600))));
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
        properties.setShadow(false);
        properties.setMaxJourneysPerRequest(500);
        properties.setShedBacklogThreshold(0);
    }

    // ── 처리 중이 아닐 때: 앞당긴다 ───────────────────────────────────────────

    @Test
    @DisplayName("처리 중이 아니면 다음 평가를 직전 평가 + 최소 간격까지 앞당긴다")
    void pullsForwardToLastEvaluatedPlusGap() {
        Long id = startJourney();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        scheduleAt(id, now.plusSeconds(120), now.minusSeconds(2));

        TriggerResult result = request(ReevaluationTier.CAUTION, id);

        assertThat(result.delivered()).containsExactly(id);
        // 직전 평가 2초 전 + 주의 최소 간격 10초 = 지금부터 약 8초 뒤
        assertThat(nextEvaluationAt(id)).isBetween(now.plusSeconds(6), now.plusSeconds(10));
        assertThat(dirty(id)).isFalse();
    }

    @Test
    @DisplayName("이미 더 이른 예정은 뒤로 미루지 않는다")
    void neverPushesLater() {
        Long id = startJourney();
        Instant soon = Instant.now().plusSeconds(2).truncatedTo(ChronoUnit.MILLIS);
        scheduleAt(id, soon, Instant.now().minusSeconds(1));

        request(ReevaluationTier.RELAXED, id);

        assertThat(nextEvaluationAt(id)).isEqualTo(soon);
    }

    @Test
    @DisplayName("한 번도 평가하지 않은 여정(처리 중 표시 NULL)도 오류 없이 지금으로 당긴다")
    void neverEvaluatedJourneyIsPulledToNow() {
        Long id = startJourney();
        Instant before = Instant.now();
        scheduleAt(id, before.plusSeconds(120), null);

        request(ReevaluationTier.URGENT, id);

        assertThat(nextEvaluationAt(id)).isBefore(before.plusSeconds(2));
    }

    // ── 처리 중일 때: 표시만 ─────────────────────────────────────────────────

    @Test
    @DisplayName("처리 중이면 선점 증서를 건드리지 않고 dirty 만 세운다 → 완료는 저장되고 곧 다시 평가된다")
    void claimedJourneyGetsDirtyThenFinishReschedulesSoon() {
        Long id = startJourney();
        JourneyDueClaim claim = claimOnly(id);

        request(ReevaluationTier.URGENT, id);

        assertThat(nextEvaluationAt(id)).as("증서가 바뀌면 진행 중 계산이 결과를 버린다")
                .isEqualTo(claim.leaseUntil());
        assertThat(dirty(id)).isTrue();

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        int owned = finish(claim, now.plusSeconds(120), now, 5);

        assertThat(owned).isEqualTo(1);
        assertThat(nextEvaluationAt(id)).isEqualTo(now.plusSeconds(5));
        assertThat(dirty(id)).isFalse();
        assertThat(claimedUntil(id)).isNull();
        assertThat(lastEvaluatedAt(id)).isEqualTo(now);
    }

    @Test
    @DisplayName("dirty 완료도 정책이 정한 더 이른 시각은 그대로 따른다")
    void dirtyFinishKeepsEarlierPolicyTime() {
        Long id = startJourney();
        JourneyDueClaim claim = claimOnly(id);
        request(ReevaluationTier.RELAXED, id);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        finish(claim, now.plusSeconds(10), now, 30);

        assertThat(nextEvaluationAt(id)).isEqualTo(now.plusSeconds(10));
    }

    @Test
    @DisplayName("선점은 죽은 워커가 남긴 dirty 를 지운다")
    void claimClearsStaleDirty() {
        Long id = startJourney();
        jdbcTemplate.update("""
                update journey_session set evaluation_dirty = true,
                       next_evaluation_at = now() - interval '5 seconds'
                 where id = ?""", id);

        claimOnly(id);

        assertThat(dirty(id)).isFalse();
    }

    @Test
    @DisplayName("livelock 없음: 매 계산 도중 이벤트가 와도 모든 평가가 저장된다")
    void eventsFasterThanEvaluationNeverStarveTheResult() {
        Long id = startJourney();
        for (int round = 0; round < 5; round++) {
            makeDue(id);
            JourneyDueClaim claim = claimOnly(id);
            // 계산 시간보다 잦은 이벤트
            request(ReevaluationTier.URGENT, id);
            request(ReevaluationTier.URGENT, id);
            request(ReevaluationTier.URGENT, id);

            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            assertThat(finish(claim, now.plusSeconds(60), now, 5))
                    .as("round %d 의 결과가 버려지면 이벤트가 잦은 여정은 영영 저장되지 않는다", round)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("선점 시간이 지난 여정에 트리거가 와도 증서는 그대로 — 늦게 끝난 워커도 저장된다")
    void expiredLeaseKeepsTokenForSlowWorker() {
        Long id = startJourney();
        JourneyDueClaim original = claimOnly(id);
        // 워커가 느려 선점 시간이 지난 상태(아직 아무도 다시 잡지 않음). 선점은 증서와 처리 중 표시를
        // 같은 값으로 적으므로 둘이 함께 지난다.
        Instant expired = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        jdbcTemplate.update("""
                update journey_session set next_evaluation_at = ?, evaluation_claimed_until = ?
                 where id = ?""", Timestamp.from(expired), Timestamp.from(expired), id);
        JourneyDueClaim claim = new JourneyDueClaim(id, expired, original.dueAt());

        request(ReevaluationTier.URGENT, id);

        assertThat(nextEvaluationAt(id)).as("LEAST(지난 증서, …) 는 지난 증서라 소유권이 보존된다")
                .isEqualTo(expired);
        Instant now = Instant.now();
        assertThat(finish(claim, now.plusSeconds(30), now, 5)).isEqualTo(1);
    }

    @Test
    @DisplayName("단계 전이로 결과가 버려진 뒤 남은 처리 중 표시는 dirty 만 세우고, 다음 선점이 둘 다 지운다")
    void staleClaimAfterLostFinishIsClearedByNextClaim() {
        Long id = startJourney();
        claimOnly(id);
        // 단계 전이가 증서를 깼다: 다음 평가는 지금, 처리 중 표시는 lease 까지 남아 있다
        jdbcTemplate.update("update journey_session set next_evaluation_at = now() where id = ?", id);

        request(ReevaluationTier.URGENT, id);
        assertThat(dirty(id)).isTrue();

        claimOnly(id);
        assertThat(dirty(id)).isFalse();
    }

    @Test
    @DisplayName("선점 트랜잭션이 잡고 있는 동안 온 트리거는 기다렸다가 처리 중으로 보고 dirty 만 세운다")
    void triggerDuringClaimTransactionEndsDirty() throws Exception {
        Long id = startJourney();
        makeDue(id);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Instant> lease = pool.submit(() -> tx.execute(status -> {
                Instant leaseUntil = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
                sessionRepository.claimDueBatch(Instant.now(), leaseUntil, 500, false, Instant.now());
                claimed.countDown();
                await(release);
                return leaseUntil;
            }));
            assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
            Future<TriggerResult> triggered = pool.submit(() -> request(ReevaluationTier.URGENT, id));
            Thread.sleep(300);
            assertThat(triggered.isDone()).as("행 잠금 때문에 선점 커밋까지 기다려야 한다").isFalse();
            release.countDown();

            Instant leaseUntil = lease.get(10, TimeUnit.SECONDS);
            triggered.get(10, TimeUnit.SECONDS);
            assertThat(dirty(id)).isTrue();
            assertThat(nextEvaluationAt(id)).isEqualTo(leaseUntil);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("겹치는 여정을 반대 순서로 담은 두 트리거가 동시에 와도 교착하지 않는다")
    void overlappingBatchesDoNotDeadlock() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(startJourney());
        }
        List<Long> reversed = new ArrayList<>(ids);
        java.util.Collections.reverse(reversed);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                Instant now = Instant.now();
                Future<?> a = pool.submit(() -> tx.execute(s ->
                        sessionRepository.requestReevaluation(ids, now, 5)));
                Future<?> b = pool.submit(() -> tx.execute(s ->
                        sessionRepository.requestReevaluation(reversed, now, 5)));
                a.get(10, TimeUnit.SECONDS);
                b.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("잠금 없이 읽은 엔티티를 저장해도 그사이 트리거가 당긴 시각과 dirty 를 되돌리지 않는다")
    void entitySaveDoesNotRevertTriggerColumns() {
        Long id = startJourney();
        Instant later = Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.MILLIS);
        scheduleAt(id, later, Instant.now().minusSeconds(60));

        tx.executeWithoutResult(status -> {
            JourneySession session = sessionRepository.findById(id).orElseThrow();
            // 다른 트랜잭션의 트리거가 그사이 당긴다
            new TransactionTemplate(transactionManager) {{
                setPropagationBehavior(PROPAGATION_REQUIRES_NEW);
            }}.executeWithoutResult(inner -> request(ReevaluationTier.URGENT, id));
            session.recordRerouteIgnored();
        });

        assertThat(nextEvaluationAt(id)).as("@DynamicUpdate 가 없으면 옛 예정 시각으로 되돌아간다")
                .isBefore(later);
    }

    // ── 대상 거르기·운영 장치 ─────────────────────────────────────────────────

    @Test
    @DisplayName("끝난 여정·삭제된 여정·단독 추적 여정은 건드리지 않는다")
    void ignoresInactiveDeletedAndTrackJourneys() {
        Long ended = startJourney();
        Long deleted = startJourney();
        Long track = startJourney();
        Instant later = Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.MILLIS);
        for (Long id : List.of(ended, deleted, track)) {
            scheduleAt(id, later, null);
        }
        jdbcTemplate.update("update journey_session set status = 'EXPIRED', end_reason = 'CLIENT_LEASE_EXPIRED', ended_at = now() where id = ?", ended);
        jdbcTemplate.update("update journey_session set deleted_at = now() where id = ?", deleted);
        jdbcTemplate.update("update journey_session set kind = 'TRACK' where id = ?", track);

        request(ReevaluationTier.URGENT, ended, deleted, track);

        for (Long id : List.of(ended, deleted, track)) {
            assertThat(nextEvaluationAt(id)).isEqualTo(later);
            assertThat(dirty(id)).isFalse();
        }
    }

    @Test
    @DisplayName("트리거는 급함 우선 표시를 바꾸지 않는다 — 그 근거는 평가 결과 하나다")
    void doesNotTouchUrgentFlag() {
        Long id = startJourney();
        jdbcTemplate.update("update journey_session set evaluation_urgent = false where id = ?", id);

        request(ReevaluationTier.URGENT, id);

        assertThat(jdbcTemplate.queryForObject(
                "select evaluation_urgent from journey_session where id = ?", Boolean.class, id)).isFalse();
    }

    @Test
    @DisplayName("그림자 모드는 DB 를 고치지 않고 반영했다고만 센다")
    void shadowModeWritesNothing() {
        Long id = startJourney();
        Instant later = Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.MILLIS);
        scheduleAt(id, later, null);
        properties.setShadow(true);

        TriggerResult result = request(ReevaluationTier.URGENT, id);

        assertThat(result.delivered()).containsExactly(id);
        assertThat(nextEvaluationAt(id)).isEqualTo(later);
    }

    @Test
    @DisplayName("요청 상한을 넘긴 여정은 미뤄 돌려준다 — 급한 등급부터 담는다")
    void overflowIsDeferredUrgentFirst() {
        Long urgent = startJourney();
        Long relaxed = startJourney();
        properties.setMaxJourneysPerRequest(1);

        TriggerResult result = trigger.request(Map.of(
                ReevaluationTier.URGENT, Set.of(urgent),
                ReevaluationTier.RELAXED, Set.of(relaxed)));

        assertThat(result.delivered()).containsExactly(urgent);
        assertThat(result.deferred()).containsExactly(relaxed);
    }

    @Test
    @DisplayName("평가기가 밀려 있으면 주의·여유는 미루고 긴급만 보낸다")
    void saturatedEvaluatorShedsLessUrgentTiers() {
        Long urgent = startJourney();
        Long caution = startJourney();
        // 예정보다 30초 넘게 밀린 여정 하나만 있어도 포화로 보게 기준을 1로 낮춘다
        jdbcTemplate.update("update journey_session set next_evaluation_at = now() - interval '5 minutes' where id = ?", urgent);
        properties.setShedBacklogThreshold(1);

        TriggerResult result = trigger.request(Map.of(
                ReevaluationTier.URGENT, Set.of(urgent),
                ReevaluationTier.CAUTION, Set.of(caution)));

        assertThat(result.delivered()).containsExactly(urgent);
        assertThat(result.deferred()).containsExactly(caution);
    }

    @Test
    @DisplayName("한 여정이 여러 등급에 있으면 가장 급한 등급으로 한 번만 친다")
    void journeyInSeveralTiersCountsOnceAsMostUrgent() {
        Long id = startJourney();
        Instant now = Instant.now();
        scheduleAt(id, now.plusSeconds(120), now);

        trigger.request(Map.of(
                ReevaluationTier.URGENT, Set.of(id),
                ReevaluationTier.RELAXED, Set.of(id)));

        // 긴급 최소 간격 5초 — 여유 30초가 이기면 30초 뒤가 된다
        assertThat(nextEvaluationAt(id)).isBefore(now.plusSeconds(7));
    }

    @Test
    @DisplayName("단계 전이는 지금처럼 진행 중 계산의 증서를 깬다 — 경로가 바뀌어 옛 결과가 무효다")
    void legTransitionStillBreaksInFlightClaim() {
        Long id = startJourney();
        JourneyDueClaim claim = claimOnly(id);
        Long ownerId = jdbcTemplate.queryForObject(
                "select user_id from journey_session where id = ?", Long.class, id);

        applyJourneyEventUseCase.apply(ownerId, id,
                new JourneyCommand(UUID.randomUUID(), JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                        1L, 1, 0, 1, Instant.now()));

        Instant now = Instant.now();
        assertThat(finish(claim, now.plusSeconds(30), now, 5)).isZero();
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private TriggerResult request(ReevaluationTier tier, Long... ids) {
        return trigger.request(Map.of(tier, Set.of(ids)));
    }

    private JourneyDueClaim claimOnly(Long id) {
        makeDue(id);
        for (int i = 0; i < 20; i++) {
            Optional<JourneyDueClaim> mine = claimService.claimDue().stream()
                    .filter(c -> c.journeyId().equals(id)).findFirst();
            if (mine.isPresent()) {
                return mine.get();
            }
        }
        throw new AssertionError("선점하지 못함: " + id);
    }

    private int finish(JourneyDueClaim claim, Instant next, Instant now, long dirtyGapSeconds) {
        Integer owned = tx.execute(s -> sessionRepository.finishEvaluation(
                claim.journeyId(), claim.leaseUntil(), next, false, false, now, dirtyGapSeconds));
        return owned == null ? 0 : owned;
    }

    private void makeDue(Long id) {
        jdbcTemplate.update("update journey_session set next_evaluation_at = now() - interval '1 second' where id = ?", id);
    }

    private void scheduleAt(Long id, Instant next, Instant lastEvaluatedAt) {
        jdbcTemplate.update("""
                update journey_session set next_evaluation_at = ?, last_evaluated_at = ?,
                       evaluation_claimed_until = null, evaluation_dirty = false
                 where id = ?""",
                Timestamp.from(next), lastEvaluatedAt == null ? null : Timestamp.from(lastEvaluatedAt), id);
    }

    private Instant nextEvaluationAt(Long id) {
        return jdbcTemplate.queryForObject(
                "select next_evaluation_at from journey_session where id = ?", Instant.class, id);
    }

    private Instant claimedUntil(Long id) {
        return jdbcTemplate.queryForObject(
                "select evaluation_claimed_until from journey_session where id = ?", Instant.class, id);
    }

    private Instant lastEvaluatedAt(Long id) {
        return jdbcTemplate.queryForObject(
                "select last_evaluated_at from journey_session where id = ?", Instant.class, id);
    }

    private boolean dirty(Long id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select evaluation_dirty from journey_session where id = ?", Boolean.class, id));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Long startJourney() {
        Long ownerId = newUser();
        JourneySnapshot journey = startJourneyUseCase.start(ownerId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
        return journey.journeyId();
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
