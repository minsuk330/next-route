package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneyTransferRiskProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.dto.TransferTargetArrival;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

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
import static org.mockito.Mockito.verify;

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
    private TransferRiskEvaluator evaluator;

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
        evaluator = new TransferRiskEvaluator(transferRepository, legRepository, arrivalService,
                targetArrivalService,
                new TransferReadinessCalculator(properties),
                new TransferRiskPolicy(properties),
                assessmentWriter,
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
    @DisplayName("환승이 없으면 아무것도 하지 않는다")
    void noTransfersMeansEmpty() {
        given(transferRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                JOURNEY_ID, REVISION)).willReturn(List.of());

        assertThat(evaluate()).isEmpty();
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
