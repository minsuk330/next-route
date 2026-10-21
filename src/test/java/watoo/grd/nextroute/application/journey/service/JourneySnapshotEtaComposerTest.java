package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.config.JourneyTransferRiskProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.JourneyLegView;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyEtaView.NextAction;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.SubwayGuidance;
import watoo.grd.nextroute.application.journey.dto.SubwayRingEtaSource;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPhase;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.dto.SubwayRingStage;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.dto.TransferTargetArrival;
import watoo.grd.nextroute.application.journey.port.in.GetJourneyNavigationGeometryUseCase;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("스냅샷에 현재 단계·환승·목적지 예측을 얹는다")
class JourneySnapshotEtaComposerTest {

    private static final long JOURNEY_ID = 7L;
    private static final long USER_ID = 42L;
    private static final int REVISION = 1;
    private static final Instant NOW = Instant.parse("2026-08-04T09:00:00Z");

    private JourneyLegRepository legRepository;
    private GetJourneyNavigationGeometryUseCase geometryUseCase;
    private CurrentLegArrivalService arrivalService;
    private TransferRiskEvaluator transferRiskEvaluator;
    private StoredJourneyEvaluationReader storedEvaluationReader;
    private JourneyEvaluatorProperties evaluatorProperties;
    private JourneySnapshotEtaComposer composer;

    /** 0=RIDE_BUS(현재), 1=TRANSFER_WALK(120초), 2=WAIT_BUS, 3=RIDE_BUS(600초), 4=WALK(180초) */
    private List<JourneyLeg> legs;

    @BeforeEach
    void setUp() {
        legRepository = mock(JourneyLegRepository.class);
        geometryUseCase = mock(GetJourneyNavigationGeometryUseCase.class);
        arrivalService = mock(CurrentLegArrivalService.class);
        transferRiskEvaluator = mock(TransferRiskEvaluator.class);
        storedEvaluationReader = mock(StoredJourneyEvaluationReader.class);
        // 평가기가 꺼진 상태를 기본으로 둔다 — 조회 경로가 스스로 계산하고 저장하는 동작이다.
        evaluatorProperties = new JourneyEvaluatorProperties();
        composer = new JourneySnapshotEtaComposer(
                legRepository, geometryUseCase, arrivalService, transferRiskEvaluator,
                storedEvaluationReader, evaluatorProperties, new ChosenSubwayTrainWait(),
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        legs = List.of(
                leg(0, JourneyLegType.RIDE_BUS, 1200),
                leg(1, JourneyLegType.TRANSFER_WALK, 120),
                leg(2, JourneyLegType.WAIT_BUS, null),
                leg(3, JourneyLegType.RIDE_BUS, 600),
                leg(4, JourneyLegType.WALK, 180));
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                JOURNEY_ID, REVISION)).willReturn(legs);
        given(geometryUseCase.get(anyLong(), anyLong(), anyInt()))
                .willReturn(new JourneyNavigationGeometry(JOURNEY_ID, REVISION, List.of(), null));
        given(transferRiskEvaluator.assessAndStore(anyLong(), anyInt(), anyInt(), any())).willReturn(List.of());
    }

    @Test
    @DisplayName("현재 단계 ETA 를 절대 시각과 관측 시각까지 담는다")
    void fillsCurrentLegEta() {
        givenCurrentArrival(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));

        JourneySnapshot result = composer.compose(USER_ID, snapshot(0));

        assertThat(result.eta().currentLeg().arrivalAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(result.eta().currentLeg().observedAt()).isEqualTo(NOW);
        assertThat(result.eta().currentLeg().quality()).isEqualTo(CurrentLegArrival.Quality.GOOD);
    }

    @Test
    @DisplayName("버스를 타고 있으면 하차 예측을 담고 체크포인트는 담지 않는다")
    void fillsAlightingForBusOnly() {
        givenCurrentArrival(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));

        var alighting = composer.compose(USER_ID, snapshot(0)).eta().alighting();

        assertThat(alighting).isNotNull();
        assertThat(alighting.remainingStops()).isEqualTo(3);
        assertThat(alighting.predictedAlightingAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    @DisplayName("걷는 중에는 하차 예측이 없다")
    void noAlightingWhileWalking() {
        givenCurrentArrival(arrival(NOW.plusSeconds(100), CurrentLegArrival.Quality.GOOD));

        assertThat(composer.compose(USER_ID, snapshot(1)).eta().alighting()).isNull();
    }

    @Test
    @DisplayName("아직 지나지 않은 환승만 활성 환승으로 고른다")
    void picksUpcomingTransfer() {
        givenCurrentArrival(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        given(transferRiskEvaluator.assessAndStore(anyLong(), anyInt(), anyInt(), any())).willReturn(List.of(
                assessment(0, 0, 2, TransferStatus.CATCHABLE, 150L)));

        var transfer = composer.compose(USER_ID, snapshot(0)).eta().activeTransfer();

        assertThat(transfer).isNotNull();
        assertThat(transfer.transferIndex()).isZero();
        assertThat(transfer.transferSlackSeconds()).isEqualTo(150);
        // 성공 확률은 캘리브레이션이 준비된 뒤에 채운다
        assertThat(transfer.successProbability()).isNull();
    }

    @Test
    @DisplayName("이미 지나온 환승은 고르지 않는다")
    void skipsPassedTransfer() {
        givenCurrentArrival(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        given(transferRiskEvaluator.assessAndStore(anyLong(), anyInt(), anyInt(), any())).willReturn(List.of(
                assessment(0, 0, 2, TransferStatus.COMFORTABLE, 400L)));

        // 이미 4번 단계(마지막 걷기)까지 왔으면 2번 대기 단계 환승은 지나간 것이다
        assertThat(composer.compose(USER_ID, snapshot(4)).eta().activeTransfer()).isNull();
    }

    @Test
    @DisplayName("목적지 ETA 는 남은 단계를 이어 더하고 대기 단계는 다음 차량 도착으로 다시 기준 잡는다")
    void composesJourneyEta() {
        givenCurrentArrival(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        given(transferRiskEvaluator.assessAndStore(anyLong(), anyInt(), anyInt(), any())).willReturn(List.of(
                assessment(0, 0, 2, TransferStatus.CATCHABLE, 150L)));

        Instant journeyEta = composer.compose(USER_ID, snapshot(0)).eta().journeyEta();

        // 09:05 도착 → 도보 120초 → 대기 단계에서 09:10(다음 버스)로 재기준 → 승차 600초 → 걷기 180초
        assertThat(journeyEta).isEqualTo(Instant.parse("2026-08-04T09:23:00Z"));
    }

    @Test
    @DisplayName("대기 단계의 다음 차량 시각을 모르면 목적지 ETA 를 만들지 않는다")
    void unknownWaitMeansUnknownJourneyEta() {
        givenCurrentArrival(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        given(transferRiskEvaluator.assessAndStore(anyLong(), anyInt(), anyInt(), any())).willReturn(List.of());

        assertThat(composer.compose(USER_ID, snapshot(0)).eta().journeyEta()).isNull();
    }

    @Test
    @DisplayName("환승이 위험하면 다음 행동이 환승 준비이고 폴링을 좁힌다")
    void atRiskTransferChangesActionAndPolling() {
        givenCurrentArrival(arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));
        given(transferRiskEvaluator.assessAndStore(anyLong(), anyInt(), anyInt(), any())).willReturn(List.of(
                assessment(0, 0, 2, TransferStatus.AT_RISK, 30L)));

        JourneySnapshot result = composer.compose(USER_ID, snapshot(0));

        assertThat(result.eta().nextAction().type()).isEqualTo(NextAction.Type.PREPARE_TRANSFER);
        assertThat(result.recommendedPollSeconds()).isEqualTo(5);
    }

    @Test
    @DisplayName("마지막 걷기는 목적지로 가는 행동이다")
    void lastWalkGoesToDestination() {
        givenCurrentArrival(arrival(NOW.plusSeconds(180), CurrentLegArrival.Quality.GOOD));

        assertThat(composer.compose(USER_ID, snapshot(4)).eta().nextAction().type())
                .isEqualTo(NextAction.Type.WALK_TO_DESTINATION);
    }

    @Test
    @DisplayName("끝난 여정은 예측도 실시간 조회도 하지 않는다")
    void finishedJourneyIsUntouched() {
        JourneySnapshot finished = JourneySnapshot.of(JOURNEY_ID, JourneyStatus.COMPLETED, JourneyKind.NAVIGATION,
                5L, 9L, REVISION, 4, NOW.plusSeconds(180), null, NOW, views());

        JourneySnapshot result = composer.compose(USER_ID, finished);

        assertThat(result.eta()).isNull();
        verify(transferRiskEvaluator, never()).assessAndStore(anyLong(), anyInt(), anyInt(), any());
        verify(transferRiskEvaluator, never()).assess(anyLong(), anyInt(), anyInt(), any());
        verify(geometryUseCase, never()).get(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("평가기가 켜져 있으면 저장된 판정을 읽고 실시간 조회를 하지 않는다")
    void readsStoredAssessmentWhileEvaluatorRuns() {
        evaluatorProperties.setEnabled(true);
        given(storedEvaluationReader.assessments(anyLong(), anyInt(), any()))
                .willReturn(List.of(assessment(0, 0, 2, TransferStatus.CATCHABLE, 150L)));
        givenCurrentArrival(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));

        JourneySnapshot result = composer.compose(USER_ID, snapshot(0));

        assertThat(result.eta().activeTransfer().status()).isEqualTo(TransferStatus.CATCHABLE);
        assertThat(result.eta().evaluatedAt()).isEqualTo(NOW);
        verify(storedEvaluationReader).assessments(anyLong(), anyInt(), any());
        verify(transferRiskEvaluator, never()).assessAndStore(anyLong(), anyInt(), anyInt(), any());
        verify(transferRiskEvaluator, never()).assess(anyLong(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("기다리는 단계가 현재면 저장된 도착 예측을 읽는다 — 실시간을 부르지 않는다")
    void readsStoredBoardingArrivalForWaitingLeg() {
        evaluatorProperties.setEnabled(true);
        given(storedEvaluationReader.assessments(anyLong(), anyInt(), any())).willReturn(List.of());
        given(storedEvaluationReader.boardingArrival(any(), any()))
                .willReturn(arrival(NOW.plusSeconds(240), CurrentLegArrival.Quality.GOOD));

        JourneySnapshot result = composer.compose(USER_ID, snapshot(2));

        assertThat(result.eta().currentLeg().arrivalAt()).isEqualTo(NOW.plusSeconds(240));
        verify(storedEvaluationReader).boardingArrival(any(), any());
        verify(arrivalService, never()).predict(any(), any());
    }

    @Test
    @DisplayName("이동 단계는 평가기가 켜져 있어도 요청 시점에 계산한다")
    void ridingLegIsStillComputedPerRequest() {
        evaluatorProperties.setEnabled(true);
        given(storedEvaluationReader.assessments(anyLong(), anyInt(), any())).willReturn(List.of());
        givenCurrentArrival(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));

        JourneySnapshot result = composer.compose(USER_ID, snapshot(0));

        assertThat(result.eta().currentLeg().arrivalAt()).isEqualTo(NOW.plusSeconds(300));
        verify(arrivalService).predict(any(), any());
        // 지금 단계(탑승)는 저장값을 읽지 않는다. 뒤쪽 대기의 승차 예측은 목적지 도착에 쓰려고 읽는다(#480).
        verify(storedEvaluationReader, never()).boardingArrival(
                org.mockito.ArgumentMatchers.argThat(leg -> leg != null && leg.getLegIndex() == 0), any());
    }

    @Test
    @DisplayName("#480 안내 시작 직후(첫 도보) 첫 승차 대기에 저장된 예측이 있으면 목적지 도착이 나온다 — 대기를 0 으로 두지 않는다")
    void firstWaitPredictionGivesJourneyEtaRightAfterStart() {
        evaluatorProperties.setEnabled(true);
        useSubwayRoute();   // 0=WALK(120, 현재) 1=WAIT_SUBWAY 2=RIDE_SUBWAY(120) 3=WALK(300)
        given(storedEvaluationReader.assessments(anyLong(), anyInt(), any())).willReturn(List.of());
        givenCurrentArrival(arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));
        // 평가기가 첫 대기에 적어 둔 승차 예측: 09:05(걸어서 닿는 09:02 뒤 3분)
        given(storedEvaluationReader.boardingArrival(
                org.mockito.ArgumentMatchers.argThat(leg -> leg != null && leg.getLegIndex() == 1), any()))
                .willReturn(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.APPROXIMATE));

        JourneySnapshot result = composer.compose(USER_ID, snapshot(0));

        // 09:05 승차 + 120초 탑승 + 300초 걷기 = 09:12
        assertThat(result.eta().journeyEta()).isEqualTo(NOW.plusSeconds(720));
    }

    @Test
    @DisplayName("#480 대기가 둘이면 각 대기가 자기 승차 예측으로 넘어간다 — 첫 대기에 둘째 대기의 차량 시각을 쓰지 않는다")
    void eachWaitUsesItsOwnPrediction() {
        evaluatorProperties.setEnabled(true);
        legs = List.of(
                leg(0, JourneyLegType.WALK, 60),
                leg(1, JourneyLegType.WAIT_BUS, null),
                leg(2, JourneyLegType.RIDE_BUS, 600),
                leg(3, JourneyLegType.TRANSFER_WALK, 120),
                leg(4, JourneyLegType.WAIT_BUS, null),
                leg(5, JourneyLegType.RIDE_BUS, 300));
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                JOURNEY_ID, REVISION)).willReturn(legs);
        given(storedEvaluationReader.assessments(anyLong(), anyInt(), any())).willReturn(List.of());
        givenCurrentArrival(arrival(NOW.plusSeconds(60), CurrentLegArrival.Quality.GOOD));
        given(storedEvaluationReader.boardingArrival(
                org.mockito.ArgumentMatchers.argThat(leg -> leg != null && leg.getLegIndex() == 1), any()))
                .willReturn(arrival(NOW.plusSeconds(240), CurrentLegArrival.Quality.GOOD));
        given(storedEvaluationReader.boardingArrival(
                org.mockito.ArgumentMatchers.argThat(leg -> leg != null && leg.getLegIndex() == 4), any()))
                .willReturn(arrival(NOW.plusSeconds(1200), CurrentLegArrival.Quality.APPROXIMATE));

        JourneySnapshot result = composer.compose(USER_ID, snapshot(0));

        // 09:04 승차 + 600 + 120 = 09:16 도착, 둘째 차 09:20 승차 + 300 = 09:25
        assertThat(result.eta().journeyEta()).isEqualTo(NOW.plusSeconds(1500));
    }

    @Test
    @DisplayName("#480 뒤쪽 대기 예측이 없으면 지금처럼 모름이다 — 대기 시간을 0 으로 지어내지 않는다")
    void missingLaterWaitPredictionIsUnknown() {
        evaluatorProperties.setEnabled(true);
        useSubwayRoute();
        given(storedEvaluationReader.assessments(anyLong(), anyInt(), any())).willReturn(List.of());
        givenCurrentArrival(arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));
        given(storedEvaluationReader.boardingArrival(any(), any())).willReturn(CurrentLegArrival.unknown());

        assertThat(composer.compose(USER_ID, snapshot(0)).eta().journeyEta()).isNull();
    }

    @Test
    @DisplayName("평가기가 꺼져 있으면 계산 시각을 알려 주지 않는다 — 방금 계산한 값이다")
    void evaluatedAtIsNullWithoutEvaluator() {
        givenCurrentArrival(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));

        JourneySnapshot result = composer.compose(USER_ID, snapshot(0));

        assertThat(result.eta().evaluatedAt()).isNull();
    }

    @Test
    @DisplayName("좌표 조회가 실패해도 예측을 포기하지 않는다")
    void geometryFailureIsTolerated() {
        given(geometryUseCase.get(anyLong(), anyLong(), anyInt()))
                .willThrow(new IllegalStateException("좌표 없음"));
        givenCurrentArrival(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.APPROXIMATE));

        JourneySnapshot result = composer.compose(USER_ID, snapshot(0));

        assertThat(result.eta().currentLeg().arrivalAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    @DisplayName("현재 단계 ETA 를 모르면 목적지 ETA 도 모른다")
    void unknownCurrentLegMeansUnknownJourneyEta() {
        givenCurrentArrival(CurrentLegArrival.unknown());

        JourneySnapshot result = composer.compose(USER_ID, snapshot(0));

        assertThat(result.eta().currentLeg().quality()).isEqualTo(CurrentLegArrival.Quality.UNKNOWN);
        assertThat(result.eta().journeyEta()).isNull();
        assertThat(result.eta().alighting()).isNull();
    }

    // ── 아직 승차역에 오지 않은 지하철 열차(#469) ─────────────────────────────
    //
    // 0=WALK(120), 1=WAIT_SUBWAY, 2=RIDE_SUBWAY(120, 현재), 3=WALK(300). 방금 열차를 골라 타는 단계가 됐고,
    // 이동 단계 계산은 "지금 + 계획 120초"를 낸다. 열차는 5분 뒤(09:05) 승차역에 온다.

    private static final Instant TRAIN_AT_BOARDING = NOW.plusSeconds(300);

    @Test
    @DisplayName("고른 열차가 아직 승차역 전이면 현재 단계 도착은 링의 하차 예정이다 — 열차 대기가 들어간다")
    void trainNotYetAtBoardingUsesRingAlighting() {
        useSubwayRoute();
        givenCurrentArrival(arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));
        // 링: 09:05 승차역 도착 + 하차역까지 150초 → 09:07:30
        Instant ringAlighting = TRAIN_AT_BOARDING.plusSeconds(150);

        JourneySnapshot result = composer.compose(USER_ID,
                snapshot(2).withSubwayGuidance(subwayGuidance(0, TRAIN_AT_BOARDING, ringAlighting)));

        assertThat(result.eta().currentLeg().arrivalAt()).isEqualTo(ringAlighting);
        assertThat(result.eta().currentLeg().remainingSeconds()).isEqualTo(450);
        assertThat(result.eta().currentLeg().source()).isEqualTo(CurrentLegArrival.Source.REALTIME_ARRIVAL);
        assertThat(result.eta().currentLeg().quality()).isEqualTo(CurrentLegArrival.Quality.GOOD);
        // 열차 승차역 도착(09:05) + 구간(150초) + 남은 걷기(300초) = 09:12:30
        assertThat(result.eta().journeyEta()).isEqualTo(Instant.parse("2026-08-04T09:12:30Z"));
        assertThat(result.eta().nextAction().at()).isEqualTo(ringAlighting);
        // 응답 모양은 그대로다 — 지하철 하차 예측은 여전히 싣지 않는다
        assertThat(result.eta().alighting()).isNull();
    }

    @Test
    @DisplayName("링에 하차 예정이 없으면 열차의 승차역 도착에 계획 소요시간을 더한다")
    void trainNotYetAtBoardingWithoutRingAlightingAddsWaitToPlanned() {
        useSubwayRoute();
        givenCurrentArrival(arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));

        JourneySnapshot result = composer.compose(USER_ID,
                snapshot(2).withSubwayGuidance(subwayGuidance(0, TRAIN_AT_BOARDING, null)));

        // 09:05 + 계획 120초 = 09:07, + 걷기 300초 = 09:12
        assertThat(result.eta().currentLeg().arrivalAt()).isEqualTo(TRAIN_AT_BOARDING.plusSeconds(120));
        assertThat(result.eta().journeyEta()).isEqualTo(Instant.parse("2026-08-04T09:12:00Z"));
    }

    @Test
    @DisplayName("열차 도착 예정이 이미 지났는데 아직 안 왔으면 지금부터 계획 소요시간을 센다")
    void lateTrainCountsFromNow() {
        useSubwayRoute();
        givenCurrentArrival(arrival(NOW.minusSeconds(60), CurrentLegArrival.Quality.APPROXIMATE));

        JourneySnapshot result = composer.compose(USER_ID,
                snapshot(2).withSubwayGuidance(subwayGuidance(0, NOW.minusSeconds(30), null)));

        assertThat(result.eta().currentLeg().arrivalAt()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    @DisplayName("링이 열차가 언제 오는지 모르면 지어내지 않고 이동 단계 계산을 그대로 둔다")
    void unknownTrainArrivalKeepsMovingEstimate() {
        useSubwayRoute();
        givenCurrentArrival(arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));

        JourneySnapshot result = composer.compose(USER_ID,
                snapshot(2).withSubwayGuidance(subwayGuidance(0, null, null)));

        assertThat(result.eta().currentLeg().arrivalAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(result.eta().currentLeg().source())
                .isEqualTo(CurrentLegArrival.Source.CLIENT_GEOMETRY_PROGRESS);
        assertThat(result.eta().journeyEta()).isEqualTo(NOW.plusSeconds(420));
    }

    @Test
    @DisplayName("#481 탄 뒤에도 링의 하차 예정이 있으면 그것이다 — 화면 요약과 잠금화면이 같은 도착을 말한다")
    void afterBoardingUsesRingAlighting() {
        useSubwayRoute();
        // 앱 진행도는 지하에서 끊겨 "탑승 + 계획"에 고정됐다
        givenCurrentArrival(arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));

        // 링이 다음 구간(1)을 향한다 — 열차가 승차역을 떠났고, 늦어서 하차 예정은 200초 뒤다
        JourneySnapshot result = composer.compose(USER_ID,
                snapshot(2).withSubwayGuidance(subwayGuidance(1, NOW.plusSeconds(90), NOW.plusSeconds(200))));

        assertThat(result.eta().currentLeg().arrivalAt()).isEqualTo(NOW.plusSeconds(200));
        assertThat(result.eta().currentLeg().source()).isEqualTo(CurrentLegArrival.Source.REALTIME_ARRIVAL);
        assertThat(result.eta().currentLeg().quality()).isEqualTo(CurrentLegArrival.Quality.GOOD);
        // 하차 예정 + 남은 걷기 300초
        assertThat(result.eta().journeyEta()).isEqualTo(NOW.plusSeconds(500));
    }

    @Test
    @DisplayName("#481 탄 뒤 링이 시간 따라 늦어지면 journeyEta 도 같이 움직인다(1분 안)")
    void afterBoardingJourneyEtaFollowsTheRing() {
        useSubwayRoute();
        givenCurrentArrival(arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));

        for (int alighting : new int[]{200, 320, 440}) {
            JourneySnapshot result = composer.compose(USER_ID, snapshot(2).withSubwayGuidance(
                    subwayGuidance(1, NOW.plusSeconds(90), NOW.plusSeconds(alighting))));
            assertThat(result.eta().journeyEta()).isEqualTo(NOW.plusSeconds(alighting + 300L));
        }
    }

    @Test
    @DisplayName("#481 탄 뒤 링이 멈췄어도(STALE) 마지막 하차 예정을 쓰되 품질은 낮춘다. 이미 지났으면 지금")
    void afterBoardingStaleRingUsesLastAlighting() {
        useSubwayRoute();
        givenCurrentArrival(arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));
        SubwayRingState stale = ringState(1, NOW.plusSeconds(90), NOW.plusSeconds(200))
                .withQuality(SubwayRingQuality.STALE);
        SubwayRingState overdue = ringState(1, NOW.minusSeconds(60), NOW.minusSeconds(10));

        var fromStale = composer.compose(USER_ID, snapshot(2).withSubwayGuidance(
                SubwayGuidance.of(SubwayBindingStatus.CONFIRMED, "서울2호선", stale))).eta().currentLeg();
        var fromOverdue = composer.compose(USER_ID, snapshot(2).withSubwayGuidance(
                SubwayGuidance.of(SubwayBindingStatus.CONFIRMED, "서울2호선", overdue))).eta().currentLeg();

        assertThat(fromStale.arrivalAt()).isEqualTo(NOW.plusSeconds(200));
        assertThat(fromStale.quality()).isEqualTo(CurrentLegArrival.Quality.APPROXIMATE);
        assertThat(fromOverdue.arrivalAt()).isEqualTo(NOW);
        assertThat(fromOverdue.remainingSeconds()).isZero();
    }

    @Test
    @DisplayName("#481 탄 뒤 링에 하차 예정이 없으면 지금과 같다 — 앱 진행도")
    void afterBoardingWithoutRingAlightingKeepsMovingEstimate() {
        useSubwayRoute();
        givenCurrentArrival(arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));

        JourneySnapshot result = composer.compose(USER_ID,
                snapshot(2).withSubwayGuidance(subwayGuidance(1, NOW.plusSeconds(90), null)));

        assertThat(result.eta().currentLeg().arrivalAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(result.eta().currentLeg().source())
                .isEqualTo(CurrentLegArrival.Source.CLIENT_GEOMETRY_PROGRESS);
        assertThat(result.eta().journeyEta()).isEqualTo(NOW.plusSeconds(420));
    }

    @Test
    @DisplayName("열차를 아직 못 정했으면(링 없음) 지금과 같다")
    void pendingBindingKeepsMovingEstimate() {
        useSubwayRoute();
        givenCurrentArrival(arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));

        JourneySnapshot result = composer.compose(USER_ID, snapshot(2).withSubwayGuidance(
                SubwayGuidance.pending(SubwayBindingStatus.PENDING, "서울2호선", "교대")));

        assertThat(result.eta().currentLeg().arrivalAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(result.eta().journeyEta()).isEqualTo(NOW.plusSeconds(420));
    }

    @Test
    @DisplayName("열차 도착이 추정(TYPICAL)이거나 링이 멈췄으면 품질을 낮춘다")
    void typicalOrStaleRingIsApproximate() {
        useSubwayRoute();
        givenCurrentArrival(arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));
        SubwayRingState typical = ringState(0, TRAIN_AT_BOARDING, TRAIN_AT_BOARDING.plusSeconds(150))
                .withSource(SubwayRingEtaSource.TYPICAL, SubwayRingStage.RUN);
        SubwayRingState stale = ringState(0, TRAIN_AT_BOARDING, TRAIN_AT_BOARDING.plusSeconds(150))
                .withQuality(SubwayRingQuality.STALE);

        var fromTypical = composer.compose(USER_ID, snapshot(2).withSubwayGuidance(
                SubwayGuidance.of(SubwayBindingStatus.CONFIRMED, "서울2호선", typical))).eta().currentLeg();
        var fromStale = composer.compose(USER_ID, snapshot(2).withSubwayGuidance(
                SubwayGuidance.of(SubwayBindingStatus.CONFIRMED, "서울2호선", stale))).eta().currentLeg();

        assertThat(fromTypical.arrivalAt()).isEqualTo(TRAIN_AT_BOARDING.plusSeconds(150));
        assertThat(fromTypical.quality()).isEqualTo(CurrentLegArrival.Quality.APPROXIMATE);
        assertThat(fromStale.quality()).isEqualTo(CurrentLegArrival.Quality.APPROXIMATE);
    }

    @Test
    @DisplayName("조회 경로의 환승 판정도 같은 열차 대기 규칙을 쓴다 — 지하철→버스 여정 도착이 낙관적이지 않다(#476)")
    void transferAssessmentUsesSameChosenTrainWait() {
        // 0=WAIT_SUBWAY, 1=RIDE_SUBWAY(현재, 120초), 2=TRANSFER_WALK(120초), 3=WAIT_BUS, 4=RIDE_BUS(600초), 5=WALK(180초)
        legs = List.of(
                leg(0, JourneyLegType.WAIT_SUBWAY, null),
                leg(1, JourneyLegType.RIDE_SUBWAY, 120),
                leg(2, JourneyLegType.TRANSFER_WALK, 120),
                leg(3, JourneyLegType.WAIT_BUS, null),
                leg(4, JourneyLegType.RIDE_BUS, 600),
                leg(5, JourneyLegType.WALK, 180));
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                JOURNEY_ID, REVISION)).willReturn(legs);
        // 열차 대기 없는 이동 계산은 09:02 하차다. 고른 열차는 09:05 에 와서 09:07 에 하차역에 닿는다.
        givenCurrentArrival(arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));
        SubwayGuidance guidance = subwayGuidance(0, TRAIN_AT_BOARDING, TRAIN_AT_BOARDING.plusSeconds(120));

        // 판정기는 진짜로 돌린다(평가기 꺼짐 = 조회 경로가 계산). 링은 스냅샷과 같은 조립기에서 읽는다.
        TransferConnectionRepository transferRepository = mock(TransferConnectionRepository.class);
        given(transferRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                JOURNEY_ID, REVISION)).willReturn(List.of(TransferConnection.builder()
                .journeyId(JOURNEY_ID).routeRevision(REVISION).transferIndex(0)
                .fromLegIndex(1).walkLegIndex(2).toLegIndex(3).build()));
        JourneySubwayGuidanceComposer guidanceComposer = mock(JourneySubwayGuidanceComposer.class);
        given(guidanceComposer.compose(eq(REVISION), eq(1), any(), any())).willReturn(guidance);
        // 버스는 09:06·09:10·09:20 에 온다. 준비 시각 뒤 첫 버스를 답한다.
        TransferTargetArrivalService targetArrivalService = mock(TransferTargetArrivalService.class);
        willAnswer(call -> {
            Instant readyAt = call.getArgument(1);
            Instant bus = List.of(NOW.plusSeconds(360), NOW.plusSeconds(600), NOW.plusSeconds(1200)).stream()
                    .filter(at -> !at.isBefore(readyAt)).findFirst().orElseThrow();
            return TransferTargetArrival.of(arrival(bus, CurrentLegArrival.Quality.GOOD));
        }).given(targetArrivalService).predictBoarding(eq(legs.get(3)), any(), any());

        ChosenSubwayTrainWait sharedRule = spy(new ChosenSubwayTrainWait());
        JourneyTransferRiskProperties riskProperties = new JourneyTransferRiskProperties();
        java.time.Clock clock = java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC);
        TransferRiskEvaluator evaluator = new TransferRiskEvaluator(transferRepository, legRepository,
                arrivalService, targetArrivalService, new TransferReadinessCalculator(riskProperties),
                new TransferRiskPolicy(riskProperties), new TransferAssessmentWriter(transferRepository),
                guidanceComposer, sharedRule,
                new watoo.grd.nextroute.application.journey.observability.JourneyEvaluationMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                riskProperties, clock);
        JourneySnapshotEtaComposer requestPath = new JourneySnapshotEtaComposer(legRepository, geometryUseCase,
                arrivalService, evaluator, storedEvaluationReader, evaluatorProperties, sharedRule, clock);

        JourneySnapshot result = requestPath.compose(USER_ID, snapshot(1).withSubwayGuidance(guidance));

        assertThat(result.eta().currentLeg().arrivalAt()).isEqualTo(Instant.parse("2026-08-04T09:07:00Z"));
        var transfer = result.eta().activeTransfer();
        // 현재 단계 도착에서 이어진다: 09:07 + 하차 30초 + 도보 120초. 대기를 빼면 09:04:30 이라 09:06 버스를 탄다고 본다
        assertThat(transfer.userReadyAt()).isEqualTo(Instant.parse("2026-08-04T09:09:30Z"));
        assertThat(transfer.nextBusArrivalAt()).isEqualTo(Instant.parse("2026-08-04T09:10:00Z"));
        assertThat(transfer.transferSlackSeconds()).isEqualTo(30);
        // 09:10 버스 + 600 + 180. 대기를 빼면 09:06 버스 기준 09:19 로 낙관적이었다
        assertThat(result.eta().journeyEta()).isEqualTo(Instant.parse("2026-08-04T09:23:00Z"));
        // 조립기(현재 단계)와 판정기(환승 준비)가 같은 규칙을 같은 안내로 불렀다
        verify(sharedRule, times(2)).afterChosenTrainArrives(eq(guidance), eq(legs.get(1)), any(), eq(NOW));
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private void useSubwayRoute() {
        legs = List.of(
                leg(0, JourneyLegType.WALK, 120),
                leg(1, JourneyLegType.WAIT_SUBWAY, null),
                leg(2, JourneyLegType.RIDE_SUBWAY, 120),
                leg(3, JourneyLegType.WALK, 300));
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                JOURNEY_ID, REVISION)).willReturn(legs);
    }

    /** 강남(0, 승차역) → 교대(1, 하차역). 열차 도착 예정은 실시간(LIVE)이다. */
    private SubwayGuidance subwayGuidance(int nextStationSequence, Instant nextStationAt, Instant alightingAt) {
        return SubwayGuidance.of(SubwayBindingStatus.CONFIRMED, "서울2호선",
                ringState(nextStationSequence, nextStationAt, alightingAt));
    }

    private SubwayRingState ringState(int nextStationSequence, Instant nextStationAt, Instant alightingAt) {
        return new SubwayRingState(JOURNEY_ID, REVISION, 2, 0,
                nextStationSequence, "100000022" + nextStationSequence,
                nextStationSequence == 0 ? "강남" : "교대", "교대", 1,
                BigDecimal.ZERO, NOW, BigDecimal.ZERO, nextStationAt, NOW.plusSeconds(600),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, NOW, NOW, 1L)
                .withSource(nextStationAt == null ? null : SubwayRingEtaSource.LIVE, SubwayRingStage.RUN)
                .withEstimatedAlightingAt(alightingAt, SubwayRingEtaSource.TYPICAL);
    }

    private void givenCurrentArrival(CurrentLegArrival arrival) {
        given(arrivalService.predict(any(), any())).willReturn(arrival);
    }

    private JourneySnapshot snapshot(int currentLegIndex) {
        return JourneySnapshot.of(JOURNEY_ID, JourneyStatus.ACTIVE, JourneyKind.NAVIGATION, 3L, 7L, REVISION,
                currentLegIndex, NOW.plusSeconds(180), null, NOW, views());
    }

    private List<JourneyLegView> views() {
        return legs.stream()
                .map(leg -> new JourneyLegView(leg.getId(), leg.getLegIndex(), leg.getType(),
                        leg.getState(), null, null, null, null, null,
                        leg.getPlannedDurationSec(), null, null, List.of()))
                .toList();
    }

    private CurrentLegArrival arrival(Instant at, CurrentLegArrival.Quality quality) {
        return new CurrentLegArrival(at,
                (int) (at.getEpochSecond() - NOW.getEpochSecond()),
                1840, 3, CurrentLegArrival.Source.CLIENT_GEOMETRY_PROGRESS, quality, NOW);
    }

    private TransferRiskAssessment assessment(int transferIndex, int fromLegIndex, int toLegIndex,
                                              TransferStatus status, Long slack) {
        return new TransferRiskAssessment(transferIndex, fromLegIndex, toLegIndex, status,
                NOW.plusSeconds(300), NOW.plusSeconds(450), NOW.plusSeconds(600),
                slack, CurrentLegArrival.Quality.GOOD, NOW,
                CurrentLegArrival.Source.REALTIME_ARRIVAL, null);
    }

    private JourneyLeg leg(int index, JourneyLegType type, Integer plannedSeconds) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(JOURNEY_ID)
                .routeRevision(REVISION)
                .legIndex(index)
                .type(type)
                .state(JourneyLegState.ACTIVE)
                .plannedDurationSec(plannedSeconds)
                .build();
        ReflectionTestUtils.setField(leg, "id", 100L + index);
        return leg;
    }
}
