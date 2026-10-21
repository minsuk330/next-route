package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneyTransferRiskProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.SubwayGuidance;
import watoo.grd.nextroute.application.journey.dto.SubwayRingEtaSource;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPhase;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.dto.SubwayRingStage;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.dto.TransferAssessmentBatch;
import watoo.grd.nextroute.application.journey.dto.TransferTargetArrival;
import watoo.grd.nextroute.application.journey.observability.JourneyEvaluationMetrics;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("환승 위험도를 판정하고 근거와 함께 남긴다")
class TransferRiskEvaluatorTest {

    private static final long JOURNEY_ID = 7L;
    private static final int REVISION = 1;

    /** 기본 픽스처는 사용자가 아직 앞 버스를 타고 있는 상황이다(환승 from leg). */
    private static final int RIDING_LEG_INDEX = 0;
    private static final Instant NOW = Instant.parse("2026-08-04T09:00:00Z");

    private TransferConnectionRepository transferRepository;
    private JourneyLegRepository legRepository;
    private CurrentLegArrivalService arrivalService;
    private TransferTargetArrivalService targetArrivalService;
    private TransferAssessmentWriter assessmentWriter;
    private JourneySubwayGuidanceComposer guidanceComposer;
    /** 조립기와 같은 규칙 — 진짜 규칙을 감싸 판정기가 부르는지 확인한다. */
    private ChosenSubwayTrainWait chosenTrainWait;
    private TransferRiskEvaluator evaluator;
    private final JourneyTransferRiskProperties riskProperties = new JourneyTransferRiskProperties();
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    /** 0=RIDE_BUS(환승 전), 1=TRANSFER_WALK(120초), 2=WAIT_BUS(환승 대상) */
    private JourneyLeg rideLeg;
    private JourneyLeg walkLeg;
    private JourneyLeg waitLeg;

    @BeforeEach
    void setUp() {
        transferRepository = mock(TransferConnectionRepository.class);
        legRepository = mock(JourneyLegRepository.class);
        arrivalService = mock(CurrentLegArrivalService.class);
        targetArrivalService = mock(TransferTargetArrivalService.class);
        given(targetArrivalService.predictBoarding(any(), any(), any()))
                .willReturn(TransferTargetArrival.unknown());

        JourneyTransferRiskProperties properties = new JourneyTransferRiskProperties();
        assessmentWriter = new TransferAssessmentWriter(transferRepository);
        guidanceComposer = mock(JourneySubwayGuidanceComposer.class);
        chosenTrainWait = spy(new ChosenSubwayTrainWait());
        evaluator = new TransferRiskEvaluator(transferRepository, legRepository, arrivalService,
                targetArrivalService,
                new TransferReadinessCalculator(properties),
                new TransferRiskPolicy(properties),
                assessmentWriter,
                guidanceComposer,
                chosenTrainWait,
                new watoo.grd.nextroute.application.journey.observability.JourneyEvaluationMetrics(meterRegistry),
                riskProperties,
                Clock.fixed(NOW, ZoneOffset.UTC));

        rideLeg = leg(0, JourneyLegType.RIDE_BUS, 1200);
        walkLeg = leg(1, JourneyLegType.TRANSFER_WALK, 120);
        waitLeg = leg(2, JourneyLegType.WAIT_BUS, null);
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                JOURNEY_ID, REVISION)).willReturn(List.of(rideLeg, walkLeg, waitLeg));
    }

    @Test
    @DisplayName("도착·하차버퍼·도보·다음차량으로 여유를 구한다")
    void computesSlackFromThreeTimes() {
        TransferConnection transfer = transfer(0, 0, 1, 2, null);
        givenTransfers(transfer);
        // 버스가 09:05 도착 → +30초 하차 +120초 도보 = 09:07:30 준비 완료
        givenArrival(rideLeg, arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        // 다음 버스가 09:10 도착 → 여유 150초 → CATCHABLE
        givenBoarding(arrival(NOW.plusSeconds(600), CurrentLegArrival.Quality.GOOD));

        TransferRiskAssessment assessment = evaluate().get(0);

        assertThat(assessment.userReadyAt()).isEqualTo(Instant.parse("2026-08-04T09:07:30Z"));
        assertThat(assessment.transferSlackSeconds()).isEqualTo(150);
        assertThat(assessment.status()).isEqualTo(TransferStatus.CATCHABLE);
        assertThat(assessment.confidence()).isEqualTo(CurrentLegArrival.Quality.GOOD);
    }

    @Test
    @DisplayName("판정 결과를 근거 시각과 함께 저장한다")
    void persistsAssessment() {
        TransferConnection transfer = transfer(0, 0, 1, 2, null);
        givenTransfers(transfer);
        givenArrival(rideLeg, arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        givenBoarding(arrival(NOW.plusSeconds(600), CurrentLegArrival.Quality.GOOD));

        evaluate();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.CATCHABLE);
        assertThat(transfer.getTransferSlackSeconds()).isEqualTo(150);
        assertThat(transfer.getUserReadyAt()).isEqualTo(Instant.parse("2026-08-04T09:07:30Z"));
        assertThat(transfer.getNextBusArrivalAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(transfer.getCalculatedAt()).isEqualTo(NOW);
        assertThat(transfer.getConfidence()).isEqualTo("GOOD");
    }

    @Test
    @DisplayName("알림 기록은 건드리지 않는다 — 판정과 발송은 다른 일이다")
    void doesNotTouchNotificationRecord() {
        TransferConnection transfer = transfer(0, 0, 1, 2, null);
        ReflectionTestUtils.setField(transfer, "lastNotifiedStatus", "AT_RISK");
        givenTransfers(transfer);
        givenArrival(rideLeg, arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        givenBoarding(arrival(NOW.plusSeconds(600), CurrentLegArrival.Quality.GOOD));

        evaluate();

        assertThat(transfer.getLastNotifiedStatus()).isEqualTo("AT_RISK");
    }

    @Test
    @DisplayName("한쪽 ETA 를 모르면 여유를 만들지 않고 모름으로 남긴다")
    void unknownEtaMeansUnknownStatus() {
        TransferConnection transfer = transfer(0, 0, 1, 2, TransferStatus.CATCHABLE);
        givenTransfers(transfer);
        givenArrival(rideLeg, arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        givenBoarding(CurrentLegArrival.unknown());

        TransferRiskAssessment assessment = evaluate().get(0);

        assertThat(assessment.status()).isEqualTo(TransferStatus.UNKNOWN);
        assertThat(assessment.transferSlackSeconds()).isNull();
        assertThat(assessment.confidence()).isEqualTo(CurrentLegArrival.Quality.UNKNOWN);
    }

    @Test
    @DisplayName("두 ETA 중 하나가 근사면 판정 신뢰도도 근사다")
    void approximateEtaLowersConfidence() {
        givenTransfers(transfer(0, 0, 1, 2, null));
        givenArrival(rideLeg, arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.APPROXIMATE));
        givenBoarding(arrival(NOW.plusSeconds(600), CurrentLegArrival.Quality.GOOD));

        assertThat(evaluate().get(0).confidence())
                .isEqualTo(CurrentLegArrival.Quality.APPROXIMATE);
    }

    @Test
    @DisplayName("같은 정류장 환승은 도보 없이 하차 버퍼만 센다")
    void sameStopTransferHasNoWalk() {
        givenTransfers(transfer(0, 0, null, 2, null));
        givenArrival(rideLeg, arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        givenBoarding(arrival(NOW.plusSeconds(600), CurrentLegArrival.Quality.GOOD));

        // 09:05 + 30초 = 09:05:30 준비, 09:10 도착 → 여유 270초
        assertThat(evaluate().get(0).transferSlackSeconds()).isEqualTo(270);
    }

    @Test
    @DisplayName("떨림 방지가 직전 저장 상태를 근거로 동작한다")
    void hysteresisUsesStoredStatus() {
        // 여유 150초는 CATCHABLE 구간이지만 직전이 AT_RISK 면 임계+60=180 이 필요하다.
        givenTransfers(transfer(0, 0, 1, 2, TransferStatus.AT_RISK));
        givenArrival(rideLeg, arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        givenBoarding(arrival(NOW.plusSeconds(600), CurrentLegArrival.Quality.GOOD));

        assertThat(evaluate().get(0).status()).isEqualTo(TransferStatus.AT_RISK);
    }

    @Test
    @DisplayName("환승도 남은 대기도 없으면 아무것도 하지 않는다 — 실시간 조회 없음")
    void noTransfersNoWaitsMeansEmpty() {
        given(transferRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                JOURNEY_ID, REVISION)).willReturn(List.of());
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                JOURNEY_ID, REVISION)).willReturn(List.of(rideLeg, walkLeg));

        TransferAssessmentBatch batch = evaluator.assess(JOURNEY_ID, REVISION, RIDING_LEG_INDEX, Map.of());

        assertThat(batch.assessments()).isEmpty();
        assertThat(batch.boardingByLegIndex()).isEmpty();
        verifyNoInteractions(targetArrivalService);
    }

    @Test
    @DisplayName("#480 환승이 없어도 첫 승차 대기는 예측한다 — 그 대기에 서 있으면 앵커는 지금")
    void firstBoardingWaitIsPredictedWithoutTransfers() {
        given(transferRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                JOURNEY_ID, REVISION)).willReturn(List.of());
        givenBoarding(arrival(NOW.plusSeconds(240), CurrentLegArrival.Quality.GOOD));

        TransferAssessmentBatch batch = evaluator.assess(JOURNEY_ID, REVISION, 2, Map.of());

        assertThat(batch.assessments()).isEmpty();
        verify(targetArrivalService).predictBoarding(eq(waitLeg), eq(NOW), eq(NOW));
        assertThat(batch.boardingByLegIndex().get(2).arrival().arrivalAt()).isEqualTo(NOW.plusSeconds(240));
        assertThat(meterRegistry.find(JourneyEvaluationMetrics.WAIT_AHEAD)
                .tag("position", "current").tag("result", "known").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("#480 앞 단계가 남았으면 그 단계 도착 + 계획 소요시간이 첫 대기의 앵커다(안내 시작 직후)")
    void firstWaitAnchorRollsForwardFromCurrentLeg() {
        given(transferRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                JOURNEY_ID, REVISION)).willReturn(List.of());
        // 0=RIDE_BUS(현재, 09:05 도착) → 1=TRANSFER_WALK(120초) → 2=WAIT_BUS
        givenArrival(rideLeg, arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        givenBoarding(arrival(NOW.plusSeconds(600), CurrentLegArrival.Quality.GOOD));

        TransferAssessmentBatch batch = evaluator.assess(JOURNEY_ID, REVISION, RIDING_LEG_INDEX, Map.of());

        verify(targetArrivalService).predictBoarding(eq(waitLeg), eq(NOW.plusSeconds(420)), eq(NOW));
        assertThat(batch.boardingByLegIndex().get(2).arrival().arrivalAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(meterRegistry.find(JourneyEvaluationMetrics.WAIT_AHEAD)
                .tag("position", "later").tag("result", "known").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("#480 진행 중 환승의 대기는 판정이 못 구했어도 다시 묻지 않는다 — 실패한 조회를 같은 tick 에 두 번 부르지 않게")
    void coveredWaitIsNotAskedAgainEvenIfUnknown() {
        givenTransfers(transfer(0, 0, 1, 2, null));
        givenArrival(rideLeg, arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        // targetArrivalService 기본 = unknown

        evaluator.assess(JOURNEY_ID, REVISION, RIDING_LEG_INDEX, Map.of());

        verify(targetArrivalService, times(1)).predictBoarding(eq(waitLeg), any(), any());
    }

    @Test
    @DisplayName("#480 시작 전 환승의 대기는 첫 승차 + 계획 탑승·도보로 굴린 앵커로 묻고, 환승 판정은 예전처럼 UNKNOWN 이다")
    void preStartTransferWaitIsPredictedButAssessmentStaysUnknown() {
        // 0=WALK(현재, 60초) 1=WAIT_BUS(첫 승차) 2=RIDE_BUS(1200) 3=TRANSFER_WALK(120) 4=WAIT_BUS(환승 대상)
        JourneyLeg walk = leg(0, JourneyLegType.WALK, 60);
        JourneyLeg firstWait = leg(1, JourneyLegType.WAIT_BUS, null);
        JourneyLeg ride = leg(2, JourneyLegType.RIDE_BUS, 1200);
        JourneyLeg transferWalk = leg(3, JourneyLegType.TRANSFER_WALK, 120);
        JourneyLeg secondWait = leg(4, JourneyLegType.WAIT_BUS, null);
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(JOURNEY_ID, REVISION))
                .willReturn(List.of(walk, firstWait, ride, transferWalk, secondWait));
        givenTransfers(transfer(0, 2, 3, 4, null));
        givenArrival(walk, arrival(NOW.plusSeconds(60), CurrentLegArrival.Quality.GOOD));
        given(targetArrivalService.predictBoarding(eq(firstWait), any(), any()))
                .willReturn(TransferTargetArrival.of(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD)));
        given(targetArrivalService.predictBoarding(eq(secondWait), eq(NOW.plusSeconds(1620)), any()))
                .willReturn(TransferTargetArrival.of(arrival(NOW.plusSeconds(1800), CurrentLegArrival.Quality.APPROXIMATE)));

        TransferAssessmentBatch batch = evaluator.assess(JOURNEY_ID, REVISION, 0, Map.of());

        verify(targetArrivalService).predictBoarding(eq(firstWait), eq(NOW.plusSeconds(60)), eq(NOW));
        // 09:05 승차 + 1200 + 120 = 09:27
        verify(targetArrivalService).predictBoarding(eq(secondWait), eq(NOW.plusSeconds(1620)), eq(NOW));
        assertThat(batch.boardingByLegIndex().get(1).arrival().arrivalAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(batch.boardingByLegIndex().get(4).arrival().arrivalAt()).isEqualTo(NOW.plusSeconds(1800));
        assertThat(batch.assessments()).singleElement()
                .satisfies(a -> assertThat(a.status()).isEqualTo(TransferStatus.UNKNOWN));
    }

    @Test
    @DisplayName("#480 시작 전 환승도 판정이 답을 냈으면 그 답을 쓴다 — 같은 대기를 두 번 묻지 않는다")
    void preStartTransferAnsweredByAssessmentIsReused() {
        // 지금 = 0(걷기), 환승 from 은 아직 시작 전이지만 이동 계산이 도착을 낸 경우
        JourneyLeg walk = leg(0, JourneyLegType.WALK, 60);
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(JOURNEY_ID, REVISION))
                .willReturn(List.of(walk, leg(1, JourneyLegType.RIDE_BUS, 1200),
                        leg(2, JourneyLegType.TRANSFER_WALK, 120), leg(3, JourneyLegType.WAIT_BUS, null)));
        givenTransfers(transfer(0, 1, 2, 3, null));
        given(arrivalService.predict(any(), any())).willReturn(arrival(NOW.plusSeconds(1260), CurrentLegArrival.Quality.GOOD));
        given(targetArrivalService.predictBoarding(any(), any(), any()))
                .willReturn(TransferTargetArrival.of(arrival(NOW.plusSeconds(1600), CurrentLegArrival.Quality.GOOD)));

        TransferAssessmentBatch batch = evaluator.assess(JOURNEY_ID, REVISION, 0, Map.of());

        verify(targetArrivalService, times(1)).predictBoarding(
                org.mockito.ArgumentMatchers.argThat(l -> l != null && l.getLegIndex() == 3), any(), any());
        assertThat(batch.boardingByLegIndex().get(3).arrival().arrivalAt()).isEqualTo(NOW.plusSeconds(1600));
    }

    @Test
    @DisplayName("#480 스위치를 끄면 예전처럼 환승 대기만 — 환승이 없으면 아무것도 묻지 않는다")
    void switchOffRestoresTransferOnly() {
        riskProperties.setPredictAllWaits(false);
        given(transferRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                JOURNEY_ID, REVISION)).willReturn(List.of());
        givenBoarding(arrival(NOW.plusSeconds(240), CurrentLegArrival.Quality.GOOD));

        TransferAssessmentBatch batch = evaluator.assess(JOURNEY_ID, REVISION, 2, Map.of());

        assertThat(batch.boardingByLegIndex()).isEmpty();
        verifyNoInteractions(targetArrivalService);
    }

    @Test
    @DisplayName("#480 환승이 그 대기를 이미 예측했으면 다시 묻지 않는다 — 판정과 같은 값을 쓴다")
    void transferPredictionIsReused() {
        givenTransfers(transfer(0, 0, 1, 2, null));
        givenArrival(rideLeg, arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        givenBoarding(arrival(NOW.plusSeconds(600), CurrentLegArrival.Quality.GOOD));

        TransferAssessmentBatch batch = evaluator.assess(JOURNEY_ID, REVISION, RIDING_LEG_INDEX, Map.of());

        verify(targetArrivalService, times(1)).predictBoarding(eq(waitLeg), any(), any());
        assertThat(batch.boardingByLegIndex().get(2).arrival().arrivalAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(meterRegistry.find(JourneyEvaluationMetrics.WAIT_AHEAD).counter()).isNull();
    }

    @Test
    @DisplayName("#480 앞 단계 도착을 모르면 앵커가 없어 묻지 않는다 — 지어내지 않는다")
    void unknownCurrentArrivalStopsTheRoll() {
        given(transferRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                JOURNEY_ID, REVISION)).willReturn(List.of());
        givenArrival(rideLeg, CurrentLegArrival.unknown());

        TransferAssessmentBatch batch = evaluator.assess(JOURNEY_ID, REVISION, RIDING_LEG_INDEX, Map.of());

        assertThat(batch.boardingByLegIndex()).isEmpty();
        verifyNoInteractions(targetArrivalService);
    }

    @Test
    @DisplayName("갈아탈 차량은 사용자 준비 시각을 기준으로 묻는다 — 지금 시각이 아니다")
    void asksBoardingWithUserReadyAnchor() {
        givenTransfers(transfer(0, 0, 1, 2, null));
        givenArrival(rideLeg, arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        givenBoarding(arrival(NOW.plusSeconds(600), CurrentLegArrival.Quality.GOOD));

        evaluate();

        ArgumentCaptor<Instant> anchor = ArgumentCaptor.forClass(Instant.class);
        verify(targetArrivalService).predictBoarding(eq(waitLeg), anchor.capture(), eq(NOW));
        // 09:05 도착 + 30초 하차 + 120초 도보
        assertThat(anchor.getValue()).isEqualTo(Instant.parse("2026-08-04T09:07:30Z"));
    }

    @Test
    @DisplayName("타고 있는 단계 ETA 를 모르면 앵커가 없어 모름으로 남긴다")
    void withoutAnchorStaysUnknown() {
        givenTransfers(transfer(0, 0, 1, 2, null));
        givenArrival(rideLeg, CurrentLegArrival.unknown());

        TransferRiskAssessment assessment = evaluate().get(0);

        assertThat(assessment.userReadyAt()).isNull();
        assertThat(assessment.status()).isEqualTo(TransferStatus.UNKNOWN);
        // 앵커 없이 지금 시각으로 물으면 사용자가 도착하기 전에 지나갈 차량을 답으로 받는다.
        verify(targetArrivalService).predictBoarding(eq(waitLeg), eq(null), eq(NOW));
    }

    @Test
    @DisplayName("assess 는 저장하지 않는다 — 평가기가 켜져 있으면 쓰는 주체는 하나다")
    void assessDoesNotStore() {
        TransferConnection transfer = transfer(0, 0, 1, 2, TransferStatus.COMFORTABLE);
        givenTransfers(transfer);
        givenArrival(rideLeg, arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        givenBoarding(arrival(NOW.plusSeconds(600), CurrentLegArrival.Quality.GOOD));

        // 판정 결과는 CATCHABLE 이지만 저장하지 않으므로 행은 그대로다.
        assertThat(evaluator.assess(JOURNEY_ID, REVISION, RIDING_LEG_INDEX, Map.of()).assessments().get(0).status())
                .isEqualTo(TransferStatus.CATCHABLE);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMFORTABLE);
        assertThat(transfer.getCalculatedAt()).isNull();
    }

    @Test
    @DisplayName("예측 출처를 판정에 담는다 — 실시간인지 모델인지")
    void carriesPredictionSource() {
        givenTransfers(transfer(0, 0, 1, 2, null));
        givenArrival(rideLeg, arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        given(targetArrivalService.predictBoarding(eq(waitLeg), any(), any()))
                .willReturn(new TransferTargetArrival(
                        new CurrentLegArrival(NOW.plusSeconds(600), 600, null, null,
                                CurrentLegArrival.Source.MODEL_ARRIVAL,
                                CurrentLegArrival.Quality.APPROXIMATE, NOW),
                        "bus-eta-v3", false));

        TransferRiskAssessment assessment = evaluate().get(0);

        assertThat(assessment.targetSource()).isEqualTo(CurrentLegArrival.Source.MODEL_ARRIVAL);
        assertThat(assessment.modelVersion()).isEqualTo("bus-eta-v3");
    }

    // ── 고른 지하철 열차의 대기(#476) ─────────────────────────────────────────
    //
    // 지하철(현재, 계획 120초) → 환승 걷기(120초) → 버스 대기. 고른 열차는 09:05 에 승차역에 와서 09:07 에 하차역에 닿는다.
    // 다음 버스는 09:10 에 온다. 열차 대기를 빼면 09:02 하차 → 09:04:30 준비(여유 330초, 넉넉),
    // 넣으면 09:07 하차 → 09:09:30 준비(여유 30초, 위험)다.

    private static final Instant TRAIN_AT_BOARDING = Instant.parse("2026-08-04T09:05:00Z");
    private static final Instant TRAIN_AT_ALIGHTING = Instant.parse("2026-08-04T09:07:00Z");

    @Test
    @DisplayName("고른 열차가 승차역 전이면 준비 시각에 열차 대기가 들어가고 여유가 그만큼 준다")
    void chosenTrainWaitDelaysReadinessAndShrinksSlack() {
        JourneyLeg subwayLeg = useSubwayRide();
        givenTransfers(transfer(0, 0, 1, 2, null));
        CurrentLegArrival moving = arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD);
        givenArrival(subwayLeg, moving);
        SubwayGuidance guidance = guidance(0, TRAIN_AT_BOARDING, TRAIN_AT_ALIGHTING);
        given(guidanceComposer.compose(eq(REVISION), eq(0), any(), any())).willReturn(guidance);
        givenBoarding(arrival(NOW.plusSeconds(600), CurrentLegArrival.Quality.GOOD));

        TransferRiskAssessment assessment = evaluate().get(0);

        assertThat(assessment.currentLegArrivalAt()).isEqualTo(TRAIN_AT_ALIGHTING);
        // 09:07 하차 + 30초 하차 + 120초 도보
        assertThat(assessment.userReadyAt()).isEqualTo(Instant.parse("2026-08-04T09:09:30Z"));
        // 열차 대기(300초)만큼 여유가 준다: 330 → 30
        assertThat(assessment.transferSlackSeconds()).isEqualTo(30);
        assertThat(assessment.status()).isEqualTo(TransferStatus.AT_RISK);
        // 갈아탈 버스도 늦어진 준비 시각 기준으로 묻는다 — 그 전에 지나갈 버스를 답으로 받지 않는다
        ArgumentCaptor<Instant> anchor = ArgumentCaptor.forClass(Instant.class);
        verify(targetArrivalService).predictBoarding(eq(waitLeg), anchor.capture(), eq(NOW));
        assertThat(anchor.getValue()).isEqualTo(Instant.parse("2026-08-04T09:09:30Z"));
        // 조립기와 같은 규칙을 쓴다
        verify(chosenTrainWait).afterChosenTrainArrives(guidance, subwayLeg, moving, NOW);
    }

    @Test
    @DisplayName("링에 하차 예정이 없으면 열차의 승차역 도착 + 계획 소요시간으로 준비 시각을 잡는다")
    void chosenTrainWaitWithoutRingAlightingUsesPlannedRide() {
        JourneyLeg subwayLeg = useSubwayRide();
        givenTransfers(transfer(0, 0, 1, 2, null));
        givenArrival(subwayLeg, arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));
        given(guidanceComposer.compose(eq(REVISION), eq(0), any(), any()))
                .willReturn(guidance(0, TRAIN_AT_BOARDING, null));
        givenBoarding(arrival(NOW.plusSeconds(600), CurrentLegArrival.Quality.GOOD));

        TransferRiskAssessment assessment = evaluate().get(0);

        // 09:05 + 계획 120초 = 09:07 하차
        assertThat(assessment.userReadyAt()).isEqualTo(Instant.parse("2026-08-04T09:09:30Z"));
        assertThat(assessment.transferSlackSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("#481 열차를 탄 뒤(링이 승차역을 지남)에도 준비 시각은 링의 하차 예정 기준이다 — 링이 늦으면 여유가 준다")
    void boardedTrainUsesRingAlighting() {
        JourneyLeg subwayLeg = useSubwayRide();
        givenTransfers(transfer(0, 0, 1, 2, null));
        // 앱 진행도는 "탑승 + 계획"에 고정(지하에서 GPS 끊김)
        givenArrival(subwayLeg, arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));
        given(guidanceComposer.compose(eq(REVISION), eq(0), any(), any()))
                .willReturn(guidance(1, NOW.plusSeconds(60), NOW.plusSeconds(200)));
        givenBoarding(arrival(NOW.plusSeconds(600), CurrentLegArrival.Quality.GOOD));

        TransferRiskAssessment assessment = evaluate().get(0);

        assertThat(assessment.currentLegArrivalAt()).isEqualTo(NOW.plusSeconds(200));
        // 하차 예정 09:03:20 + 환승 도보 150초
        assertThat(assessment.userReadyAt()).isEqualTo(Instant.parse("2026-08-04T09:05:50Z"));
        assertThat(assessment.transferSlackSeconds()).isEqualTo(250);
    }

    @Test
    @DisplayName("링이 없으면(열차 미정·고정 없음) 준비 시각은 지금과 같다")
    void withoutRingKeepsMovingEstimate() {
        JourneyLeg subwayLeg = useSubwayRide();
        givenTransfers(transfer(0, 0, 1, 2, null));
        givenArrival(subwayLeg, arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));
        given(guidanceComposer.compose(eq(REVISION), eq(0), any(), any())).willReturn(null);
        givenBoarding(arrival(NOW.plusSeconds(600), CurrentLegArrival.Quality.GOOD));

        TransferRiskAssessment assessment = evaluate().get(0);

        assertThat(assessment.userReadyAt()).isEqualTo(Instant.parse("2026-08-04T09:04:30Z"));
        assertThat(assessment.transferSlackSeconds()).isEqualTo(330);
    }

    @Test
    @DisplayName("링을 못 읽어도 판정을 버리지 않고 열차 대기 없이 계산한다")
    void guidanceReadFailureFallsBackToMovingEstimate() {
        JourneyLeg subwayLeg = useSubwayRide();
        givenTransfers(transfer(0, 0, 1, 2, null));
        givenArrival(subwayLeg, arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));
        given(guidanceComposer.compose(eq(REVISION), eq(0), any(), any()))
                .willThrow(new IllegalStateException("db down"));
        givenBoarding(arrival(NOW.plusSeconds(600), CurrentLegArrival.Quality.GOOD));

        TransferRiskAssessment assessment = evaluate().get(0);

        assertThat(assessment.userReadyAt()).isEqualTo(Instant.parse("2026-08-04T09:04:30Z"));
        assertThat(assessment.transferSlackSeconds()).isEqualTo(330);
    }

    @Test
    @DisplayName("지하철을 타는 중이 아니면 링을 읽지 않는다 — 버스 탑승·지하철에서 내린 뒤")
    void readsRingOnlyWhileRidingSubwayIntoTransfer() {
        // 버스 탑승(기본 픽스처)
        givenTransfers(transfer(0, 0, 1, 2, null));
        givenArrival(rideLeg, arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        givenBoarding(arrival(NOW.plusSeconds(600), CurrentLegArrival.Quality.GOOD));
        evaluate();
        // 지하철에서 내려 환승 걷기 중
        JourneyLeg subwayLeg = useSubwayRide();
        givenArrival(subwayLeg, arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));
        evaluator.assess(JOURNEY_ID, REVISION, 1, Map.of());

        verifyNoInteractions(guidanceComposer);
    }

    /** 0=RIDE_SUBWAY(현재, 계획 120초), 1=TRANSFER_WALK(120초), 2=WAIT_BUS. */
    private JourneyLeg useSubwayRide() {
        JourneyLeg subwayLeg = leg(0, JourneyLegType.RIDE_SUBWAY, 120);
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                JOURNEY_ID, REVISION)).willReturn(List.of(subwayLeg, walkLeg, waitLeg));
        return subwayLeg;
    }

    /** 승차역(0) → 하차역(1) 링. 열차 도착 예정은 실시간(LIVE)이다. */
    private SubwayGuidance guidance(int nextStationSequence, Instant nextStationAt, Instant alightingAt) {
        SubwayRingState ring = new SubwayRingState(JOURNEY_ID, REVISION, 0, 0,
                nextStationSequence, "100000022" + nextStationSequence,
                nextStationSequence == 0 ? "강남" : "교대", "교대", 1,
                BigDecimal.ZERO, NOW, BigDecimal.ZERO, nextStationAt, NOW.plusSeconds(600),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, NOW, NOW, 1L)
                .withSource(nextStationAt == null ? null : SubwayRingEtaSource.LIVE, SubwayRingStage.RUN)
                .withEstimatedAlightingAt(alightingAt, SubwayRingEtaSource.TYPICAL);
        return SubwayGuidance.of(SubwayBindingStatus.CONFIRMED, "서울2호선", ring);
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private List<TransferRiskAssessment> evaluate() {
        return evaluator.assessAndStore(JOURNEY_ID, REVISION, RIDING_LEG_INDEX, Map.of());
    }

    private void givenBoarding(CurrentLegArrival arrival) {
        given(targetArrivalService.predictBoarding(eq(waitLeg), any(), any()))
                .willReturn(TransferTargetArrival.of(arrival));
    }

    private void givenTransfers(TransferConnection... transfers) {
        given(transferRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                JOURNEY_ID, REVISION)).willReturn(List.of(transfers));
    }

    private void givenArrival(JourneyLeg leg, CurrentLegArrival arrival) {
        given(arrivalService.predict(eq(leg), any())).willReturn(arrival);
    }

    private CurrentLegArrival arrival(Instant at, CurrentLegArrival.Quality quality) {
        return new CurrentLegArrival(at,
                (int) (at.getEpochSecond() - NOW.getEpochSecond()),
                null, null, CurrentLegArrival.Source.CLIENT_GEOMETRY_PROGRESS, quality, NOW);
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

    private TransferConnection transfer(int transferIndex, int fromLegIndex,
                                        Integer walkLegIndex, int toLegIndex,
                                        TransferStatus status) {
        TransferConnection transfer = TransferConnection.builder()
                .journeyId(JOURNEY_ID)
                .routeRevision(REVISION)
                .transferIndex(transferIndex)
                .fromLegIndex(fromLegIndex)
                .walkLegIndex(walkLegIndex)
                .toLegIndex(toLegIndex)
                .status(status)
                .build();
        ReflectionTestUtils.setField(transfer, "id", 500L + transferIndex);
        return transfer;
    }
}
