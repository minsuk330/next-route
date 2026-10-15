package watoo.grd.nextroute.application.journey;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import watoo.grd.nextroute.application.auth.port.in.WithdrawUseCase;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.dto.JourneyEvaluationOutcome;
import watoo.grd.nextroute.application.journey.dto.JourneyProgressCommand;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.dto.TransferAssessmentBatch;
import watoo.grd.nextroute.application.journey.event.JourneyVersionChangedEvent;
import watoo.grd.nextroute.application.journey.observability.JourneyQueryMetrics;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyProgressUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.JourneyDueClaimService;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationScheduleWriter;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.JourneyController;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.journey.CachedJourneyVersionAdapter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 여정 버전 캐시(#427 처방 4차)가 <b>실제 쓰기 경로마다</b> 커밋 뒤 덮이는지 — 실제 Postgres·Redis.
 *
 * <p>계약 하나당 시험 하나: 조건부 조회(204)로 캐시를 채우고 → 진짜 서비스로 값을 바꾸고 → 옛 번호로 다시 물으면 200
 * 이어야 한다(낡은 204 가 아니라). 캐시가 실제로 답한다는 증거는 "알림 없이 DB 만 바꾸면 여전히 204" 로 본다.
 *
 * <p>트랜잭션을 걸지 않는다 — 커밋 뒤 덮어쓰기를 보려면 서비스가 실제로 커밋해야 한다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "journey.notification.relay.enabled=false",
        "journey.query.version-cache.ttl-seconds=30"
})
@DisplayName("여정 버전 캐시")
class JourneyVersionCacheIT {

    private static final String SESSION_ID = "sess-version-cache-it";
    private static final String PATH_KEY = "0";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired ApplyJourneyEventUseCase applyJourneyEventUseCase;
    @Autowired ApplyJourneyProgressUseCase progressUseCase;
    @Autowired JourneyDueClaimService claimService;
    @Autowired JourneyEvaluationScheduleWriter scheduleWriter;
    @Autowired WithdrawUseCase withdrawUseCase;
    @Autowired JourneyController controller;
    @Autowired CachedJourneyVersionAdapter cacheAdapter;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RedisTemplate<String, String> redisTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Autowired MeterRegistry meterRegistry;
    @MockBean RouteSearchSnapshotPort snapshotPort;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private JourneySnapshot journey;

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(null, twoBusPath(), Instant.now().plusSeconds(600))));
        userId = newUser();
        journey = startJourneyUseCase.start(userId, new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    // ── 캐시가 답한다 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("시작이 커밋 뒤 캐시를 채운다 — 첫 조건부 조회부터 캐시가 답한다")
    void startWritesEntry() {
        assertThat(cached()).startsWith(userId + ":1:0:WALK:");
        assertThat(cached()).doesNotEndWith(":-");
    }

    @Test
    @DisplayName("바뀐 것 없으면 204 를 캐시가 낸다 — 알림 없이 DB 만 바꿔도 여전히 204(캐시가 답했다는 증거)")
    void unchangedServedFromCache() {
        assertThat(status(1L, 0L)).isEqualTo(204);

        jdbcTemplate.update("update journey_session set evaluation_version = evaluation_version + 9 where id = ?", id());

        assertThat(status(1L, 0L)).as("캐시가 답했으므로 DB 의 바뀐 값을 보지 않는다").isEqualTo(204);
    }

    @Test
    @DisplayName("읽는 쪽은 덮어쓰지 않는다(SET NX) — 이미 있는 칸은 그대로")
    void readPathNeverOverwrites() {
        redisTemplate.delete(key());
        String planted = userId + ":1:0:WALK:-";
        redisTemplate.opsForValue().set(key(), planted);
        jdbcTemplate.update("update journey_session set evaluation_version = 4 where id = ?", id());

        status(1L, 0L);

        assertThat(cached()).isEqualTo(planted);
    }

    @Test
    @DisplayName("캐시가 비면 DB 로 가르고 채운다 — 변경 시각은 모름(-)")
    void missFillsFromDatabase() {
        redisTemplate.delete(key());

        assertThat(status(1L, 0L)).isEqualTo(204);
        assertThat(cached()).isEqualTo(userId + ":1:0:WALK:-");
    }

    // ── 쓰기 경로마다 커밋 뒤 덮인다 ──────────────────────────────────────────

    @Test
    @DisplayName("단계 전이(명령) — 옛 번호로 물으면 200")
    void commandTransitionRefreshes() {
        assertThat(status(1L, 0L)).isEqualTo(204);

        applyJourneyEventUseCase.apply(userId, id(), new JourneyCommand(UUID.randomUUID(),
                JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED, 1L, 1, 0, 1, Instant.now()));

        assertThat(status(1L, 0L)).isEqualTo(200);
        assertThat(status(2L, 0L)).isEqualTo(204);
        assertThat(cached()).startsWith(userId + ":2:0:WAIT_BUS:");
    }

    @Test
    @DisplayName("종료(완료) — 옛 번호는 200, 끝난 여정도 새 번호면 204(예전과 같다)")
    void completeRefreshes() {
        assertThat(status(1L, 0L)).isEqualTo(204);

        applyJourneyEventUseCase.apply(userId, id(), new JourneyCommand(UUID.randomUUID(),
                JourneyEventType.JOURNEY_COMPLETED, 1L, 1, null, null, Instant.now()));

        assertThat(status(1L, 0L)).isEqualTo(200);
        assertThat(status(2L, 0L)).isEqualTo(204);
    }

    @Test
    @DisplayName("진행 보고(평가 번호) — 옛 번호로 물으면 200")
    void progressRefreshes() {
        assertThat(status(1L, 0L)).isEqualTo(204);

        progressUseCase.apply(userId, id(), new JourneyProgressCommand(UUID.randomUUID(), 1, 0,
                3, BigDecimal.valueOf(0.3), BigDecimal.valueOf(0.3), "sec-1", 1840, 420, 3,
                Instant.now(), "GOOD"));

        assertThat(status(1L, 0L)).isEqualTo(200);
        assertThat(status(1L, 1L)).isEqualTo(204);
    }

    @Test
    @DisplayName("평가 완료(finishEvaluation, 판정 바뀜) — 옛 번호로 물으면 200. 안 바뀌면 그대로 204")
    void evaluationFinishRefreshes() {
        assertThat(status(1L, 0L)).isEqualTo(204);
        Instant now = Instant.now();

        JourneyDueClaim unchanged = claim();
        scheduleWriter.finish(unchanged, new JourneyEvaluationOutcome(id(), 1, TransferAssessmentBatch.empty(), false),
                now.minusSeconds(1), false, 10, now, null);
        assertThat(status(1L, 0L)).as("판정이 그대로면 번호도 그대로").isEqualTo(204);

        JourneyDueClaim changed = claim();
        scheduleWriter.finish(changed, new JourneyEvaluationOutcome(id(), 1, TransferAssessmentBatch.empty(), true),
                now.plusSeconds(30), false, 10, now, null);

        assertThat(status(1L, 0L)).isEqualTo(200);
        assertThat(status(1L, 1L)).isEqualTo(204);
    }

    @Test
    @DisplayName("탈퇴(벌크 소프트 삭제) — 캐시가 '지워짐'으로 덮이고 조회는 예전처럼 404")
    void withdrawalRefreshes() {
        assertThat(status(1L, 0L)).isEqualTo(204);

        withdrawUseCase.withdraw(userId);

        assertThat(cached()).isEqualTo("gone");
        assertThatThrownBy(() -> status(1L, 0L)).isInstanceOf(BusinessException.class);
    }

    // ── 소유자·롤백·순서 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("남의 여정은 캐시가 차 있어도 204 가 아니라 404(예전과 같다)")
    void otherUserRejectedFromCache() {
        assertThat(status(1L, 0L)).isEqualTo(204);
        Long other = newUser();

        assertThatThrownBy(() -> controller.detail(other, id(), 1L, 0L, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("롤백된 변경은 캐시를 건드리지 않는다")
    void rollbackLeavesCache() {
        assertThat(status(1L, 0L)).isEqualTo(204);
        String before = cached();

        transactionTemplate.executeWithoutResult(tx -> {
            applyJourneyEventUseCase.apply(userId, id(), new JourneyCommand(UUID.randomUUID(),
                    JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED, 1L, 1, 0, 1, Instant.now()));
            tx.setRollbackOnly();
        });

        assertThat(cached()).isEqualTo(before);
    }

    @Test
    @DisplayName("늦게 도착한 옛 덮어쓰기는 새 값을 지우지 못한다(번호가 작아지는 덮어쓰기는 버림)")
    void olderOverwriteIsDropped() {
        String newer = userId + ":5:7:RIDE_BUS:123";
        redisTemplate.opsForValue().set(key(), newer);

        // DB 는 아직 1:0 — 늦게 커밋 뒤 읽은 쓰기를 흉내 낸다.
        transactionTemplate.executeWithoutResult(tx ->
                eventPublisher.publishEvent(JourneyVersionChangedEvent.of(id())));

        assertThat(cached()).isEqualTo(newer);
    }

    @Test
    @DisplayName("지워짐은 되살아나지 않는다")
    void tombstoneIsFinal() {
        redisTemplate.opsForValue().set(key(), "gone");

        transactionTemplate.executeWithoutResult(tx ->
                eventPublisher.publishEvent(JourneyVersionChangedEvent.of(id())));

        assertThat(cached()).isEqualTo("gone");
    }

    // ── 판단 재료 지표 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("옛 번호로 물어 200 을 받으면 커밋→수신 지연이 남는다(쓰기가 덮은 칸만)")
    void deliveryLagRecordedForStaleClient() {
        long before = lagCount();
        applyJourneyEventUseCase.apply(userId, id(), new JourneyCommand(UUID.randomUUID(),
                JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED, 1L, 1, 0, 1, Instant.now()));

        assertThat(status(1L, 0L)).isEqualTo(200);

        assertThat(lagCount()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("캐시를 DB 에서 채웠으면(변경 시각 모름) 지연을 남기지 않는다")
    void deliveryLagSkippedWhenUnknown() {
        jdbcTemplate.update("update journey_session set command_version = 2 where id = ?", id());
        redisTemplate.delete(key());
        long before = lagCount();

        assertThat(status(1L, 0L)).isEqualTo(200);

        assertThat(lagCount()).isEqualTo(before);
    }

    // ── 도구 ────────────────────────────────────────────────────────────────

    private long lagCount() {
        return meterRegistry.find(JourneyQueryMetrics.DELIVERY_LAG).timers().stream()
                .mapToLong(t -> t.count()).sum();
    }

    private int status(Long afterCommand, Long afterEvaluation) {
        return controller.detail(userId, id(), afterCommand, afterEvaluation, null).getStatusCode().value();
    }

    private JourneyDueClaim claim() {
        jdbcTemplate.update("update journey_session set next_evaluation_at = now() - interval '1 second', "
                + "evaluation_urgent = true where id = ?", id());
        return claimService.claimDue(500).stream()
                .filter(c -> c.journeyId().equals(id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("여정이 선점되지 않았다"));
    }

    private Long id() {
        return journey.journeyId();
    }

    private String key() {
        return "journey:ver:" + id();
    }

    private String cached() {
        return redisTemplate.opsForValue().get(key());
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
        jdbcTemplate.query("select id from journey_session where user_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        redisTemplate.delete("journey:ver:" + rs.getLong(1)), id);
        jdbcTemplate.update("""
                update journey_leg set selected_route_option_id = null
                where journey_id in (select id from journey_session where user_id = ?)
                """, id);
        jdbcTemplate.update("""
                delete from journey_leg_route_option where journey_leg_id in (
                    select l.id from journey_leg l join journey_session s on s.id = l.journey_id
                    where s.user_id = ?)
                """, id);
        for (String table : List.of("journey_leg", "journey_revision", "transfer_connection", "journey_event",
                "journey_telemetry_event", "reroute_proposal")) {
            jdbcTemplate.update("delete from " + table
                    + " where journey_id in (select id from journey_session where user_id = ?)", id);
        }
        jdbcTemplate.update("""
                delete from journey_notification where journey_id in (
                    select id from journey_session where user_id = ?)
                """, id);
        jdbcTemplate.update("delete from journey_session where user_id = ?", id);
    }

    private PathResult twoBusPath() {
        return new PathResult(1,
                new PathInfo(40, 2500, 600, 1, "정류장A", "정류장Z", null),
                List.of(
                        SubPathFixture.walk(3).names("출발", "정류장A").distance(150.0).build(),
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
