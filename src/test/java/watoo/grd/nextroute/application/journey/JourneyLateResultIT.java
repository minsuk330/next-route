package watoo.grd.nextroute.application.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.JourneyDueClaimService;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationService;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationService.Result;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.testsupport.MutableClock;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

/**
 * 계산하는 동안 세상이 바뀌면, 늦게 끝난 계산은 판정도 알림도 남기지 않는다.
 *
 * <p>평가기는 외부 API 를 부르는 동안 행 잠금을 쥐지 않는다. 대신 선점할 때 다음 평가 시각을
 * 미래로 밀어 두고, 저장할 때 그 값이 그대로인지 확인한다({@code finishEvaluation}). 이 시험은
 * 그 확인이 실제 경합에서 동작하는지 본다.
 *
 * <p>{@code JourneyEvaluationIT.lostClaimIsDiscarded} 와의 차이. 그 시험은 DB 값을 손으로 바꿔
 * "다른 워커가 가져갔다"를 흉내 낸다. 여기서는 <b>워커 A 를 외부 호출 안에 실제로 세워 두고</b>,
 * 그 사이 진짜 경로(다른 워커의 선점·평가, 단계 전이 명령, 여정 취소)를 돌린 뒤 A 를 풀어 준다.
 *
 * <ul>
 *   <li>A 를 세워 두는 장치: 가짜 예측기 안의 래치. A 의 스레드만 거기서 기다린다</li>
 *   <li>60초를 기다리지 않는 장치: 서비스가 읽는 시계({@link MutableClock})를 손으로 넘긴다</li>
 * </ul>
 *
 * <p>A 와 다른 쪽은 <b>서로 다른 답</b>을 받는다. A 는 "놓침"(위험 알림을 만든다), 나머지는
 * "여유"다. 그래야 끝에 남은 판정이 누구 것인지, A 의 알림이 섞였는지 가를 수 있다.
 *
 * <p>클래스에 {@code @Transactional} 을 붙이지 않는다. 붙이면 준비한 행이 커밋되지 않아 A 의
 * 스레드가 그 행을 못 보고, 커밋 순서가 만드는 경합 자체를 시험할 수 없다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("늦게 끝난 평가는 반영되지 않는다")
class JourneyLateResultIT {

    private static final String SESSION_ID = "sess-late-result-it";
    private static final String PATH_KEY = "0";
    /** A 가 받는 답. 타고 있는 사람에게 60초 뒤 오는 차는 놓친 차다. */
    private static final int A_ARRIVAL_SECONDS = 60;
    /** A 가 아닌 쪽이 받는 답. 넉넉하다. */
    private static final int OTHER_ARRIVAL_SECONDS = 900;
    private static final long WAIT_SECONDS = 10;

    @TestConfiguration
    static class ClockConfig {
        /**
         * 시작 시각을 마이크로초로 자른다. 선점 시각은 저장한 값과 <b>같음</b>으로 비교하는데,
         * Postgres 는 마이크로초까지만 담는다. 나노초가 붙은 값이면 A·B 가 모두 소유권을 잃는다.
         */
        @Bean
        @Primary
        MutableClock lateResultClock() {
            return new MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired ApplyJourneyEventUseCase applyJourneyEventUseCase;
    @Autowired JourneyDueClaimService claimService;
    @Autowired JourneyEvaluationService evaluationService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MutableClock clock;
    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean PredictTransferUseCase predictTransferUseCase;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final ExecutorService workerA = Executors.newSingleThreadExecutor(r -> new Thread(r, "worker-A"));
    /** A 가 외부 호출에 들어섰다. */
    private CountDownLatch aEntered;
    /** A 를 내보낸다. */
    private CountDownLatch releaseA;
    private Long userId;
    private Long journeyId;

    @BeforeEach
    void setUp() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        aEntered = new CountDownLatch(1);
        releaseA = new CountDownLatch(1);
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(null, busPath(), Instant.now().plusSeconds(600))));
        givenAStopsInsideTheCall();
        journeyId = startJourney();
        boardFirstBus();
        resolveStopIdentity();
    }

    @AfterEach
    void cleanUp() {
        // 단언이 실패해도 A 가 래치에 영원히 갇히지 않게 한다.
        releaseA.countDown();
        workerA.shutdownNow();
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    @Test
    @DisplayName("대조군: 아무도 끼어들지 않으면 A 의 결과는 판정과 알림으로 남는다")
    void undisturbedWorkerStoresItsResult() throws Exception {
        Future<Result> a = startWorkerA();
        releaseA.countDown();

        assertThat(a.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(Result.EVALUATED);
        assertThat(storedStatus())
                .as("이 값이 MISSED 가 아니면 아래 시험들의 '반영 0건'은 아무것도 증명하지 못한다")
                .isEqualTo(TransferStatus.MISSED.name());
        assertThat(notifications())
                .as("A 의 판정은 혼자 저장되면 위험 알림이 된다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("A 가 lease 를 넘기는 동안 B 가 가져가 저장하면, A 의 판정과 알림은 버려진다")
    void reclaimedByAnotherWorker() throws Exception {
        Future<Result> a = startWorkerA();

        clock.advance(Duration.ofSeconds(61));
        JourneyDueClaim claimB = claim();
        assertThat(evaluationService.evaluate(claimB).result()).isEqualTo(Result.EVALUATED);
        State afterB = state();

        releaseA.countDown();

        assertThat(a.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(Result.LOST_CLAIM);
        assertThat(state()).as("A 가 끝난 뒤에도 B 가 남긴 그대로여야 한다").isEqualTo(afterB);
        assertThat(afterB.status()).isNotEqualTo(TransferStatus.MISSED.name());
        assertThat(afterB.notifications()).as("A 의 위험 알림").isEmpty();
    }

    @Test
    @DisplayName("A 가 계산하는 동안 사용자가 다음 단계로 넘어가면, A 의 판정과 알림은 버려진다")
    void stepTransitionWhileComputing() throws Exception {
        Future<Result> a = startWorkerA();

        applyLegTransition(1, 2);
        State afterTransition = state();
        assertThat(afterTransition.nextEvaluationAt())
                .as("단계 전이는 다음 평가를 지금으로 앞당긴다 — A 가 심어 둔 lease 와 달라진다")
                .isEqualTo(clock.instant());

        releaseA.countDown();

        assertThat(a.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(Result.LOST_CLAIM);
        assertThat(state()).isEqualTo(afterTransition);
        assertThat(afterTransition.calculatedAt()).as("옛 단계 기준 판정이 저장되면 안 된다").isNull();
        assertThat(afterTransition.notifications()).isEmpty();
    }

    /**
     * 이 경우는 선점 시각 비교 없이도 막힌다 — 저장 조건의 {@code status = 'ACTIVE'} 가 먼저 걸린다.
     * 비교 줄을 지운 변형에서 앞의 두 시험은 깨지고 이 시험은 통과했다(2026-10-12). 두 조건이 각자
     * 다른 경우를 맡는다는 기록으로 남긴다.
     */
    @Test
    @DisplayName("A 가 계산하는 동안 여정이 끝나면, A 의 판정과 알림은 버려진다")
    void journeyEndedWhileComputing() throws Exception {
        Future<Result> a = startWorkerA();

        cancelJourney();
        State afterCancel = state();

        releaseA.countDown();

        assertThat(a.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(Result.LOST_CLAIM);
        assertThat(state()).isEqualTo(afterCancel);
        assertThat(afterCancel.nextEvaluationAt()).isNull();
        assertThat(afterCancel.calculatedAt()).isNull();
        assertThat(afterCancel.notifications()).as("끝난 여정에 위험 알림이 나가면 안 된다").isEmpty();
    }

    // ── 경합 장치 ────────────────────────────────────────────────────────────

    /** A 가 선점하고 외부 호출 안에서 멈출 때까지 기다린다. */
    private Future<Result> startWorkerA() throws InterruptedException {
        JourneyDueClaim claimA = claim();
        Future<Result> a = workerA.submit(() -> evaluationService.evaluate(claimA).result());
        assertThat(aEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("A 가 외부 호출까지 가지 못했다").isTrue();
        return a;
    }

    /**
     * 가짜 예측기. A 의 스레드만 래치에서 기다리고 "놓침"을 받는다.
     *
     * <p>도착 시각은 서비스 시계 기준이다. 시계를 넘긴 뒤의 B 도 같은 기준으로 "15분 뒤"를 받는다.
     */
    private void givenAStopsInsideTheCall() {
        willAnswer(call -> {
            boolean isA = Thread.currentThread().getName().equals("worker-A");
            if (isA) {
                aEntered.countDown();
                if (!releaseA.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("시험이 A 를 풀어 주지 않았다");
                }
            }
            Instant arrivalAt = clock.instant().plusSeconds(isA ? A_ARRIVAL_SECONDS : OTHER_ARRIVAL_SECONDS);
            return TransferPredictionResult.available(
                    call.getArgument(0), call.getArgument(1), call.getArgument(2),
                    TransferArrival.Source.REALTIME, clock.instant(), call.getArgument(3),
                    arrivalAt, "vehicle-1", null);
        }).given(predictTransferUseCase).predict(anyString(), anyString(), anyInt(), any(), any(), anyLong());
    }

    // ── 끼어드는 쪽 ──────────────────────────────────────────────────────────

    private void applyLegTransition(int from, int to) {
        var outcome = applyJourneyEventUseCase.apply(userId, journeyId,
                new JourneyCommand(UUID.randomUUID(), JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                        commandVersion(), 1, from, to, clock.instant()));
        assertThat(outcome.result().name()).isEqualTo("APPLIED");
    }

    private void cancelJourney() {
        var outcome = applyJourneyEventUseCase.apply(userId, journeyId,
                new JourneyCommand(UUID.randomUUID(), JourneyEventType.JOURNEY_CANCELED,
                        commandVersion(), 1, null, null, clock.instant()));
        assertThat(outcome.result().name()).isEqualTo("APPLIED");
    }

    // ── 관찰 ────────────────────────────────────────────────────────────────

    /** A 가 남겼을 수 있는 흔적 전부. A 를 풀기 전후로 같아야 한다. */
    private record State(String status, Instant calculatedAt, Instant nextBusArrivalAt,
                         long evaluationVersion, Instant nextEvaluationAt,
                         Instant waitLegPredictedEndAt, List<String> notifications) {
    }

    private State state() {
        return new State(
                transferColumn("status", String.class),
                transferColumn("calculated_at", Instant.class),
                transferColumn("next_bus_arrival_at", Instant.class),
                sessionColumn("evaluation_version", Long.class),
                sessionColumn("next_evaluation_at", Instant.class),
                jdbcTemplate.queryForObject("""
                        select predicted_end_at from journey_leg
                        where journey_id = ? and type = 'WAIT_BUS' and leg_index > 0 and deleted_at is null
                        order by leg_index limit 1
                        """, Instant.class, journeyId),
                notifications());
    }

    private List<String> notifications() {
        return jdbcTemplate.queryForList(
                "select checkpoint from journey_notification where journey_id = ? order by id",
                String.class, journeyId);
    }

    private String storedStatus() {
        return transferColumn("status", String.class);
    }

    private <T> T transferColumn(String column, Class<T> type) {
        return jdbcTemplate.queryForObject(
                "select " + column + " from transfer_connection"
                        + " where journey_id = ? and transfer_index = 0 and deleted_at is null",
                type, journeyId);
    }

    private <T> T sessionColumn(String column, Class<T> type) {
        return jdbcTemplate.queryForObject(
                "select " + column + " from journey_session where id = ?", type, journeyId);
    }

    private long commandVersion() {
        return sessionColumn("command_version", Long.class);
    }

    // ── 준비 (JourneyEvaluationIT 와 같은 여정) ─────────────────────────────

    private JourneyDueClaim claim() {
        return claimService.claimDue().stream()
                .filter(claim -> claim.journeyId().equals(journeyId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("여정이 선점되지 않았다"));
    }

    private Long startJourney() {
        userId = newUser();
        JourneySnapshot journey = startJourneyUseCase.start(userId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
        return journey.journeyId();
    }

    /** 대기(0) → 주행(1). 타고 있어야 하차 버퍼와 환승 도보가 더해져 60초 뒤 차가 놓친 차가 된다. */
    private void boardFirstBus() {
        var outcome = applyJourneyEventUseCase.apply(userId, journeyId,
                new JourneyCommand(UUID.randomUUID(), JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                        1L, 1, 0, 1, clock.instant()));
        assertThat(outcome.result().name()).isEqualTo("APPLIED");
    }

    /**
     * 예측기가 조회를 건너뛰지 않도록 갈아탈 정류장·노선·승차 순번을 채운다.
     *
     * <p>노선 id 는 실제로는 노선표(bus_route_stop)가 채운다. 이 시험은 그 데이터에 기대지 않는다 —
     * 비어 있으면 예측기가 "노선 미해석"으로 조회를 건너뛰어 A 가 외부 호출에 닿지도 않는다.
     */
    private void resolveStopIdentity() {
        jdbcTemplate.update("""
                update journey_leg set start_stop_id = 'ST-G'
                where journey_id = ? and type = 'WAIT_BUS' and leg_index > 0
                  and start_stop_id is null
                """, journeyId);
        jdbcTemplate.update("""
                update journey_leg_route_option
                   set boarding_seq = coalesce(boarding_seq, 3),
                       route_id = coalesce(route_id, 'BL-721')
                where journey_leg_id in (
                    select id from journey_leg
                    where journey_id = ? and type = 'WAIT_BUS' and leg_index > 0 and deleted_at is null)
                """, journeyId);
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
                "transfer_connection", "journey_event", "journey_notification")) {
            jdbcTemplate.update("delete from " + table
                    + " where journey_id in (select id from journey_session where user_id = ?)", id);
        }
        jdbcTemplate.update("delete from journey_session where user_id = ?", id);
    }

    /** 첫 버스 5분, 도보 3분, 둘째 버스 15분. 환승이 예측 시야(15분) 안에 들어온다. */
    private PathResult busPath() {
        return new PathResult(1,
                new PathInfo(25, 2500, 600, 1, "정류장A", "정류장Z", null),
                List.of(
                        SubPathFixture.bus(5).names("정류장A", "정류장F")
                                .busStops("ST-A", "ST-F")
                                .lanes(SubPathFixture.busLane("370", "BL-370")).build(),
                        SubPathFixture.walk(3).names("정류장F", "정류장G").distance(150.0).build(),
                        SubPathFixture.bus(15).names("정류장G", "정류장Z")
                                .busStops("ST-G", "ST-Z")
                                .lanes(SubPathFixture.busLane("721", "BL-721")).build()),
                List.of());
    }
}
