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
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.GetJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.JourneyDueClaimService;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationService;
import watoo.grd.nextroute.application.journey.service.JourneySnapshotEtaComposer;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * 평가기가 켜진 상태에서 <b>조회가 실시간 API 를 부르지 않는지</b> 확인한다.
 *
 * <p>이것이 이 PR 의 목적 전부다. 지금까지는 앱이 여정을 조회할 때마다 그 요청 안에서 실시간
 * 도착을 불렀고, provider 가 느리면 여정 화면도 그만큼 느려졌다. 평가기가 미리 계산해 두면
 * 조회는 읽기만 하면 된다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "journey.navigation.evaluator.enabled=true"
})
@DisplayName("조회 경로가 저장된 평가 결과를 읽는다")
class JourneyEvalReadPathIT {

    private static final String SESSION_ID = "sess-read-path-it";
    private static final String PATH_KEY = "0";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired ApplyJourneyEventUseCase applyJourneyEventUseCase;
    @Autowired GetJourneyUseCase getJourneyUseCase;
    @Autowired JourneyDueClaimService claimService;
    @Autowired JourneyEvaluationService evaluationService;
    @Autowired JourneySnapshotEtaComposer etaComposer;
    @Autowired JdbcTemplate jdbcTemplate;
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
        givenRealtimeArrival();
        userId = newUser();
        journeyId = startJourneyUseCase.start(userId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false)).journeyId();
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
    @DisplayName("평가기가 남긴 판정이 조회 응답에 실린다")
    void snapshotShowsStoredAssessment() {
        evaluate();

        JourneySnapshot snapshot = compose();

        assertThat(snapshot.eta().activeTransfer()).isNotNull();
        assertThat(snapshot.eta().activeTransfer().status()).isNotEqualTo(TransferStatus.UNKNOWN);
        assertThat(snapshot.eta().activeTransfer().nextBusArrivalAt()).isNotNull();
        assertThat(snapshot.eta().evaluatedAt()).isNotNull();
    }

    @Test
    @DisplayName("조회는 실시간 도착 API 를 부르지 않는다")
    void queryDoesNotCallRealtime() {
        evaluate();
        int callsAfterEvaluation = realtimeCalls();

        compose();
        compose();

        assertThat(realtimeCalls()).isEqualTo(callsAfterEvaluation);
    }

    @Test
    @DisplayName("평가 전에는 모름으로 답한다 — 없는 값을 만들지 않는다")
    void unknownBeforeFirstEvaluation() {
        JourneySnapshot snapshot = compose();

        assertThat(snapshot.eta().activeTransfer().status()).isEqualTo(TransferStatus.UNKNOWN);
        assertThat(snapshot.eta().activeTransfer().nextBusArrivalAt()).isNull();
        assertThat(realtimeCalls()).isZero();
    }

    @Test
    @DisplayName("저장된 도착 시각이 지나면 모름으로 내려간다")
    void staleAssessmentBecomesUnknown() {
        evaluate();
        assertThat(compose().eta().activeTransfer().nextBusArrivalAt()).isNotNull();

        // 그 버스는 떠났다.
        jdbcTemplate.update("""
                update transfer_connection set next_bus_arrival_at = now() - interval '2 minutes'
                where journey_id = ? and transfer_index = 0
                """, journeyId);

        JourneySnapshot snapshot = compose();

        assertThat(snapshot.eta().activeTransfer().status()).isEqualTo(TransferStatus.UNKNOWN);
        assertThat(snapshot.eta().activeTransfer().nextBusArrivalAt()).isNull();
    }

    @Test
    @DisplayName("타고 있는 단계 ETA 는 요청 시점에 계산한다 — 저장된 실시간 값을 읽지 않는다")
    void ridingLegEtaIsComputedPerRequest() {
        evaluate();

        JourneySnapshot snapshot = compose();

        assertThat(snapshot.eta().currentLeg().arrivalAt()).isNotNull();
        // 이 시험은 진행도를 올리지 않으므로 계획 소요시간으로 계산된다. 앱이 진행도를 올리면
        // CLIENT_GEOMETRY_PROGRESS 가 된다. 어느 쪽이든 요청 시점 계산이라는 점이 요점이다.
        assertThat(snapshot.eta().currentLeg().source()).isIn(
                CurrentLegArrival.Source.PLANNED_DURATION,
                CurrentLegArrival.Source.CLIENT_GEOMETRY_PROGRESS);
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private void evaluate() {
        evaluationService.evaluate(claimService.claimDue().stream()
                .filter(claim -> claim.journeyId().equals(journeyId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("여정이 선점되지 않았다")));
    }

    private JourneySnapshot compose() {
        return etaComposer.compose(userId,
                getJourneyUseCase.get(userId, journeyId).orElseThrow());
    }

    private int realtimeCalls() {
        return mockingDetails(predictTransferUseCase).getInvocations().size();
    }

    private void givenRealtimeArrival() {
        willAnswer(call -> {
            Instant anchor = call.getArgument(3);
            return TransferPredictionResult.available(
                    call.getArgument(0), call.getArgument(1), call.getArgument(2),
                    TransferArrival.Source.REALTIME, Instant.now(), anchor,
                    anchor.plusSeconds(150), "vehicle-1", null);
        }).given(predictTransferUseCase).predict(anyString(), anyString(), anyInt(), any(), any());
    }

    private void boardFirstBus() {
        var outcome = applyJourneyEventUseCase.apply(userId, journeyId,
                new JourneyCommand(UUID.randomUUID(),
                        JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                        1L, 1, 0, 1, Instant.now()));
        assertThat(outcome.result().name()).isEqualTo("APPLIED");
    }

    /** 정류장·노선 해석 결과를 대신 채운다. 그 해석 자체는 이 시험의 관심이 아니다. */
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
