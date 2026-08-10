package watoo.grd.nextroute.application.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

/**
 * 평가기가 실제로 무엇을 남기는지 DB 까지 확인한다.
 *
 * <p>가장 중요한 확인은 첫 번째 시험이다. <b>사용자가 아직 버스를 타고 있는 동안에도 다음 환승
 * 판정이 나와야 한다.</b> 이 PR 전에는 갈아탈 대기 단계가 시작되지 않았다는 이유로 늘 UNKNOWN
 * 이었고, 그래서 "내가 정류장에 도착할 때 그 버스가 언제 오나"를 미리 알려줄 수 없었다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("여정 평가")
class JourneyEvaluationIT {

    private static final String SESSION_ID = "sess-evaluation-it";
    private static final String PATH_KEY = "0";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired ApplyJourneyEventUseCase applyJourneyEventUseCase;
    @Autowired JourneyDueClaimService claimService;
    @Autowired JourneyEvaluationService evaluationService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("journeyEvaluatorProviderLimiter") java.util.concurrent.Semaphore providerLimiter;
    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean PredictTransferUseCase predictTransferUseCase;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private Long journeyId;

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, busPath(), Instant.now().plusSeconds(600))));
        journeyId = startJourney();
        // 버스에 탑승한 상태로 만든다. 타고 있는 단계의 도착 시각을 알아야 "그 정류장에 언제
        // 서는지"가 정해지고, 그 시각을 기준으로 갈아탈 차량을 물을 수 있다.
        boardFirstBus();
        resolveStopIdentity();
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    @Test
    @DisplayName("아직 타고 있는 동안에도 다음 환승 판정이 나온다 — 실시간으로 잡은 경우")
    void assessesUpcomingTransferWhileRiding() {
        givenRealtimeArrival(600);

        assertThat(evaluate()).isEqualTo(Result.EVALUATED);

        assertThat(storedStatus()).isNotEqualTo(TransferStatus.UNKNOWN.name());
        assertThat(storedNextBusArrivalAt()).isNotNull();
        assertThat(storedSource()).isEqualTo("REALTIME");
        assertThat(storedCalculatedAt()).isNotNull();
    }

    @Test
    @DisplayName("실시간으로 못 잡으면 모델 출처와 모델 이름을 남긴다")
    void storesModelSource() {
        givenModelArrival(700, "bus-eta-v3");

        evaluate();

        assertThat(storedSource()).isEqualTo("MODEL");
        assertThat(storedModelVersion()).isEqualTo("bus-eta-v3");
    }

    @Test
    @DisplayName("대기 단계 행에도 도착 예측을 남긴다 — 다음 PR 의 조회 경로가 읽을 값이다")
    void storesBoardingPredictionOnWaitLeg() {
        givenRealtimeArrival(600);

        evaluate();

        assertThat(waitLegPredictedEndAt()).isNotNull();
        assertThat(waitLegEvidenceSource()).isEqualTo("REALTIME_ARRIVAL");
    }

    @Test
    @DisplayName("평가가 끝나면 다음 평가 시각을 다시 심는다")
    void reschedulesAfterEvaluation() {
        givenRealtimeArrival(600);
        JourneyDueClaim claim = claim();

        evaluationService.evaluate(claim);

        Instant nextAt = nextEvaluationAt();
        assertThat(nextAt).isNotEqualTo(claim.leaseUntil());
        assertThat(nextAt).isAfter(Instant.now());
    }

    @Test
    @DisplayName("환승이 임박하면 다음 평가를 앞당긴다")
    void shortensIntervalWhenTransferIsNear() {
        ridingLegTakes(60);   // 1분 뒤 하차 → 도보까지 더해도 준비까지 5분 안이다
        givenRealtimeArrival(600);

        evaluate();

        // 임박하면 최소 간격(10초)이다. 기본 간격 30초보다 확실히 짧아야 한다.
        assertThat(secondsUntilNextEvaluation()).isLessThan(30);
    }

    @Test
    @DisplayName("환승이 멀면 다음 평가를 미룬다")
    void lengthensIntervalWhenTransferIsFar() {
        ridingLegTakes(1_800);   // 30분 뒤 하차 → 서둘러 볼 이유가 없다
        givenRealtimeArrival(600);

        evaluate();

        assertThat(secondsUntilNextEvaluation()).isGreaterThan(60);
    }

    @Test
    @DisplayName("아무것도 안 바뀌면 다음 평가를 더 미룬다")
    void lengthensIntervalWhenNothingChanges() {
        ridingLegTakes(1_800);
        givenFixedRealtimeArrival(Instant.now().plusSeconds(3_600));
        evaluate();
        long first = secondsUntilNextEvaluation();

        // 두 번째 평가가 첫 번째로부터 2분 뒤에 일어난 상황으로 만든다. 직전 간격은 저장된
        // 판정의 계산 시각에서 되읽으므로, 그 값을 과거로 밀면 시계를 건드리지 않아도 된다.
        agePreviousEvaluation(120);
        makeDue();
        evaluate();

        assertThat(secondsUntilNextEvaluation()).isGreaterThan(first);
    }

    @Test
    @DisplayName("도착 시각만 바뀌어도 평가 번호가 오른다 — 안 그러면 앱이 204 만 받는다")
    void bumpsVersionWhenOnlyArrivalTimeChanges() {
        givenRealtimeArrival(600);
        evaluate();
        long afterFirst = evaluationVersion();
        String statusAfterFirst = storedStatus();

        // 같은 판정 구간(여유가 넉넉함)이지만 버스가 조금 늦어졌다.
        givenRealtimeArrival(660);
        makeDue();
        evaluate();

        assertThat(storedStatus()).isEqualTo(statusAfterFirst);
        assertThat(evaluationVersion()).isEqualTo(afterFirst + 1);
    }

    @Test
    @DisplayName("아무 값도 안 바뀌면 평가 번호를 올리지 않는다")
    void doesNotBumpWhenNothingChanges() {
        givenFixedRealtimeArrival(Instant.now().plusSeconds(900));
        evaluate();
        long afterFirst = evaluationVersion();

        makeDue();
        evaluate();

        assertThat(evaluationVersion()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("소유권을 잃은 워커의 결과는 저장되지 않는다")
    void lostClaimIsDiscarded() {
        givenRealtimeArrival(600);
        JourneyDueClaim stale = claim();
        // 그 사이 다른 워커가 가져갔다(= 심어 둔 선점 시각이 바뀌었다).
        jdbcTemplate.update("""
                update journey_session set next_evaluation_at = now() + interval '5 minutes'
                where id = ?
                """, journeyId);

        Result result = evaluationService.evaluate(stale).result();

        assertThat(result).isEqualTo(Result.LOST_CLAIM);
        assertThat(storedCalculatedAt()).isNull();
        assertThat(storedSource()).isNull();
    }

    @Test
    @DisplayName("이미 끝난 여정은 평가하지 않는다")
    void endedJourneyIsSkipped() {
        JourneyDueClaim claim = claim();
        jdbcTemplate.update("""
                update journey_session set status = 'CANCELED', end_reason = 'USER_CANCELED',
                    ended_at = now()
                where id = ?
                """, journeyId);

        assertThat(evaluationService.evaluate(claim).result()).isEqualTo(Result.SKIPPED);
        assertThat(storedCalculatedAt()).isNull();
    }

    @Test
    @DisplayName("provider 가 막히면 다음 평가를 더 늦춘다")
    void blockedProviderBacksOff() {
        givenBlocked();
        JourneyDueClaim claim = claim();

        evaluationService.evaluate(claim);

        // 기본 간격 30초보다 훨씬 뒤여야 한다(막힘 backoff 4배).
        assertThat(nextEvaluationAt()).isAfter(Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("provider 가 잠깐 막혀도 유예 안에서는 직전 판정을 유지한다")
    void keepsPreviousAssessmentWithinGrace() {
        givenRealtimeArrival(600);
        evaluate();
        String statusBefore = storedStatus();
        Instant arrivalBefore = storedNextBusArrivalAt();
        long versionBefore = evaluationVersion();

        givenBlocked();
        makeDue();
        evaluate();

        // 조회에 실패했다고 곧바로 모름으로 덮으면, 30초 뒤 복구돼도 그 사이 사용자는 근거 없는
        // 화면을 본다. 값이 그대로이므로 평가 번호도 오르지 않아야 한다.
        assertThat(storedStatus()).isEqualTo(statusBefore);
        assertThat(storedNextBusArrivalAt()).isEqualTo(arrivalBefore);
        assertThat(evaluationVersion()).isEqualTo(versionBefore);
    }

    @Test
    @DisplayName("유예를 넘기면 모름으로 바꾼다")
    void marksUnknownAfterGrace() {
        givenRealtimeArrival(600);
        evaluate();

        // 직전 판정을 유예(기본 120초)보다 오래된 것으로 만든다.
        agePreviousEvaluation(130);
        givenBlocked();
        makeDue();
        evaluate();

        assertThat(storedStatus()).isEqualTo(TransferStatus.UNKNOWN.name());
    }

    @Test
    @DisplayName("provider 슬롯을 못 잡으면 아무것도 저장하지 않는다")
    void skipsWhenProviderSlotUnavailable() throws Exception {
        givenRealtimeArrival(600);
        JourneyDueClaim claim = claim();
        // 평가기 몫의 슬롯을 전부 잡아 둔다. 워커 스레드가 남아 있어도 외부 호출은 못 나간다.
        int permits = providerLimiter.drainPermits();
        try {
            Result result = evaluationService.evaluate(claim).result();

            assertThat(result).isEqualTo(Result.SKIPPED);
            assertThat(storedCalculatedAt()).isNull();
        } finally {
            providerLimiter.release(permits);
        }
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("정류장에서 기다리는 중이면 곧 오는 차를 놓친 것으로 보지 않는다")
    void waitingUserIsNotJudgedAsMissed() {
        walkToTransferStop();
        givenFixedRealtimeArrival(Instant.now().plusSeconds(60));

        assertThat(evaluate()).isEqualTo(Result.EVALUATED);

        assertThat(storedStatus())
                .as("이미 걸어와 서 있는 사람에게 하차 버퍼와 환승 도보를 다시 더하면 준비 시각이 "
                        + "3분 넘게 미래로 밀려, 1분 뒤 오는 차가 이미 지나간 차가 된다")
                .isNotEqualTo(TransferStatus.MISSED.name());
    }

    @Test
    @DisplayName("아직 타고 있는 중에는 하차 버퍼와 환승 도보를 그대로 더한다")
    void ridingUserStillPaysWalkAndBuffer() {
        givenFixedRealtimeArrival(Instant.now().plusSeconds(60));

        evaluate();

        assertThat(storedStatus())
                .as("타는 중에는 내려서 걸어가는 시간이 실제로 남아 있다")
                .isEqualTo(TransferStatus.MISSED.name());
    }

    /** 갈아탈 정류장까지 이동해 대기 단계에 선다. */
    private void walkToTransferStop() {
        int waitLegIndex = transferColumn("to_leg_index", Integer.class);
        long baseCommandVersion = 2L;
        for (int from = 1; from < waitLegIndex; from++) {
            var outcome = applyJourneyEventUseCase.apply(userId, journeyId,
                    new JourneyCommand(UUID.randomUUID(),
                            JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                            baseCommandVersion++, 1, from, from + 1, Instant.now()));
            assertThat(outcome.result().name()).isEqualTo("APPLIED");
        }
    }

    private Result evaluate() {
        return evaluationService.evaluate(claim()).result();
    }

    private JourneyDueClaim claim() {
        return claimService.claimDue().stream()
                .filter(claim -> claim.journeyId().equals(journeyId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("여정이 선점되지 않았다"));
    }

    /**
     * 실시간으로 다음 버스를 잡은 상황.
     *
     * <p>{@code willAnswer(...).given(mock)} 형태로 쓴다. {@code given(mock.predict(...))} 는 다시
     * 스텁할 때 <b>이미 등록된 답을 실제로 실행</b>하므로, 같은 mock 을 두 번 스텁하는 시험에서
     * 엉뚱한 곳에서 터진다.
     */
    private void givenRealtimeArrival(int secondsFromNow) {
        willAnswer(call -> {
            Instant anchor = call.getArgument(3);
            return TransferPredictionResult.available(
                    call.getArgument(0), call.getArgument(1), call.getArgument(2),
                    TransferArrival.Source.REALTIME, Instant.now(), anchor,
                    anchor.plusSeconds(secondsFromNow - 450), "vehicle-1", null);
        }).given(predictTransferUseCase).predict(anyString(), anyString(), anyInt(), any(), any(), anyLong());
    }

    private void givenFixedRealtimeArrival(Instant arrivalAt) {
        willAnswer(call -> TransferPredictionResult.available(
                call.getArgument(0), call.getArgument(1), call.getArgument(2),
                TransferArrival.Source.REALTIME, Instant.now(), call.getArgument(3),
                arrivalAt, "vehicle-1", null))
                .given(predictTransferUseCase).predict(anyString(), anyString(), anyInt(), any(), any(), anyLong());
    }

    private void givenModelArrival(int secondsFromNow, String modelVersion) {
        willAnswer(call -> {
            Instant anchor = call.getArgument(3);
            return TransferPredictionResult.available(
                    call.getArgument(0), call.getArgument(1), call.getArgument(2),
                    TransferArrival.Source.MODEL, Instant.now(), anchor,
                    anchor.plusSeconds(secondsFromNow - 450), "vehicle-1", modelVersion);
        }).given(predictTransferUseCase).predict(anyString(), anyString(), anyInt(), any(), any(), anyLong());
    }

    private void givenBlocked() {
        willAnswer(call -> TransferPredictionResult.noResult(
                call.getArgument(0), call.getArgument(1), call.getArgument(2),
                TransferArrival.Source.NONE, TransferArrival.Status.BLOCKED,
                Instant.now(), call.getArgument(3)))
                .given(predictTransferUseCase).predict(anyString(), anyString(), anyInt(), any(), any(), anyLong());
    }

    private Long startJourney() {
        userId = newUser();
        JourneySnapshot journey = startJourneyUseCase.start(userId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
        return journey.journeyId();
    }

    /** 대기(0) → 주행(1) 전이. 주행 단계가 시작되면 계획 소요시간으로 도착 시각을 잡을 수 있다. */
    private void boardFirstBus() {
        var outcome = applyJourneyEventUseCase.apply(userId, journeyId,
                new JourneyCommand(UUID.randomUUID(),
                        JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                        1L, 1, 0, 1, Instant.now()));
        // 준비가 실패하면 이어지는 확인이 모두 엉뚱한 이유로 깨진다. 여기서 바로 드러나게 한다.
        assertThat(outcome.result().name()).isEqualTo("APPLIED");
        assertThat(outcome.journey().currentLegIndex()).isEqualTo(1);
    }

    /**
     * 갈아탈 정류장과 승차 순번을 채운다.
     *
     * <p>실제로는 정류장·노선 해석(bus_route_stop 매핑)이 이 값들을 채운다. 그 데이터는 이 시험의
     * 관심이 아니지만, 값이 없으면 예측기가 "노선·정류장 미해석"으로 보고 조회를 건너뛴다 — 그래서
     * 아무 예측도 일어나지 않는다.
     */
    private void resolveStopIdentity() {
        jdbcTemplate.update("""
                update journey_leg set start_stop_id = 'ST-G'
                where journey_id = ? and type = 'WAIT_BUS' and leg_index > 0
                  and start_stop_id is null
                """, journeyId);
        jdbcTemplate.update("""
                update journey_leg_route_option set boarding_seq = 3
                where journey_leg_id in (
                    select id from journey_leg where journey_id = ? and deleted_at is null)
                  and boarding_seq is null
                """, journeyId);
    }

    /**
     * 타고 있는 단계가 얼마나 걸리는지 바꾼다.
     *
     * <p>환승까지 남은 시간을 조절하는 손잡이다. 도착 시각은 단계 시작 시각 + 계획 소요시간으로
     * 잡히고(`ClientGeometryProgressArrivalPredictor:112`), 거기에 하차 버퍼와 환승 도보가 더해져
     * 준비 시각이 된다.
     */
    private void ridingLegTakes(int seconds) {
        jdbcTemplate.update("""
                update journey_leg set planned_duration_sec = ?
                where journey_id = ? and leg_index = 1 and deleted_at is null
                """, seconds, journeyId);
    }

    /** 직전 평가가 이만큼 전에 일어난 것으로 만든다. 간격을 늘리는 규칙이 이 값을 되읽는다. */
    private void agePreviousEvaluation(int seconds) {
        jdbcTemplate.update("""
                update transfer_connection set calculated_at = calculated_at - make_interval(secs => ?)
                where journey_id = ? and deleted_at is null
                """, seconds, journeyId);
    }

    private long secondsUntilNextEvaluation() {
        return java.time.Duration.between(Instant.now(), nextEvaluationAt()).toSeconds();
    }

    /** 다시 평가 대상으로 만든다. 평가가 끝나면 다음 시각이 미래로 밀려 곧바로 선점되지 않는다. */
    private void makeDue() {
        jdbcTemplate.update("""
                update journey_session set next_evaluation_at = now() - interval '1 second'
                where id = ?
                """, journeyId);
    }

    private String storedStatus() {
        return transferColumn("status", String.class);
    }

    private String storedSource() {
        return transferColumn("source", String.class);
    }

    private String storedModelVersion() {
        return transferColumn("model_version", String.class);
    }

    private Instant storedCalculatedAt() {
        return transferColumn("calculated_at", Instant.class);
    }

    private Instant storedNextBusArrivalAt() {
        return transferColumn("next_bus_arrival_at", Instant.class);
    }

    private <T> T transferColumn(String column, Class<T> type) {
        return jdbcTemplate.queryForObject(
                "select " + column + " from transfer_connection"
                        + " where journey_id = ? and transfer_index = 0 and deleted_at is null",
                type, journeyId);
    }

    private Instant waitLegPredictedEndAt() {
        return jdbcTemplate.queryForObject("""
                select predicted_end_at from journey_leg
                where journey_id = ? and type = 'WAIT_BUS' and leg_index > 0 and deleted_at is null
                order by leg_index limit 1
                """, Instant.class, journeyId);
    }

    private String waitLegEvidenceSource() {
        return jdbcTemplate.queryForObject("""
                select evidence_source from journey_leg
                where journey_id = ? and type = 'WAIT_BUS' and leg_index > 0 and deleted_at is null
                order by leg_index limit 1
                """, String.class, journeyId);
    }

    private Instant nextEvaluationAt() {
        return jdbcTemplate.queryForObject(
                "select next_evaluation_at from journey_session where id = ?",
                Instant.class, journeyId);
    }

    private long evaluationVersion() {
        return jdbcTemplate.queryForObject(
                "select evaluation_version from journey_session where id = ?",
                Long.class, journeyId);
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

    /**
     * 첫 버스를 5분으로 둔다.
     *
     * <p>환승까지 남은 시간이 예측 시야(기본 15분)보다 길면 예측기가 조회를 건너뛰고 모른다고
     * 답한다. 그것이 올바른 동작이므로, 예측이 실제로 붙는 상황을 만들려면 환승이 시야 안에
     * 있어야 한다.
     */
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
