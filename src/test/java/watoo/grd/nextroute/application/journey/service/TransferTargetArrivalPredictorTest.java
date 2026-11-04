package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.TransferTargetArrival;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 갈아탈 차량 예측. 여기서 못박는 것은 셋이다.
 *
 * <ul>
 *   <li>기준 시각(앵커)을 그대로 넘긴다 — 사용자가 정류장에 닿을 시각이 답의 기준이다</li>
 *   <li>실시간이면 실시간, 못 잡으면 모델이라고 출처를 정확히 말한다</li>
 *   <li>앵커가 시야 밖이면 <b>외부 API 를 부르지 않는다</b> — 답이 없을 호출을 하지 않는다</li>
 * </ul>
 */
@DisplayName("갈아탈 차량 예측")
class TransferTargetArrivalPredictorTest {

    private static final Instant NOW = Instant.parse("2026-08-04T09:00:00Z");
    private static final long OPTION_ID = 42L;

    private final JourneyEvaluatorProperties properties = new JourneyEvaluatorProperties();
    private final JourneyLegRouteOptionRepository routeOptionRepository =
            mock(JourneyLegRouteOptionRepository.class);

    @Nested
    @DisplayName("버스")
    class Bus {

        private final PredictTransferUseCase predictTransferUseCase = mock(PredictTransferUseCase.class);
        private final RegionalArrivalLookup regionalArrivalLookup = mock(RegionalArrivalLookup.class);
        private BusTransferTargetArrivalPredictor predictor;
        private JourneyLeg waitLeg;

        @BeforeEach
        void setUp() {
            TransferPredictionInterpreter interpreter = new TransferPredictionInterpreter(properties);
            var metrics = new watoo.grd.nextroute.application.journey.observability.JourneyEvaluationMetrics(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
            predictor = new BusTransferTargetArrivalPredictor(
                    new BusBoardingTimeEstimator(predictTransferUseCase, interpreter, metrics),
                    routeOptionRepository, interpreter, regionalArrivalLookup, metrics);
            waitLeg = waitLeg(JourneyLegType.WAIT_BUS, JourneyLegState.PENDING);
            given(routeOptionRepository.findById(OPTION_ID)).willReturn(Optional.of(busOption()));
        }

        private JourneyLeg regionalWaitLeg(String sourceRouteId, String sourceStopId) {
            JourneyLeg leg = mock(JourneyLeg.class);
            given(leg.getSelectedRouteOptionId()).willReturn(OPTION_ID);
            given(leg.getSourceStartStopId()).willReturn(sourceStopId);
            JourneyLegRouteOption option = mock(JourneyLegRouteOption.class);
            given(option.getSourceRouteId()).willReturn(sourceRouteId);
            given(routeOptionRepository.findById(OPTION_ID)).willReturn(Optional.of(option));
            return leg;
        }

        @Test
        @DisplayName("경기 노선: 준비 시각을 기준으로 지역 실시간에 묻고 근사 품질로 답한다")
        void gyeonggiTargetUsesRegionalRealtime() {
            JourneyLeg leg = regionalWaitLeg("234001243", "101000002");
            Instant anchor = NOW.plusSeconds(300);
            given(regionalArrivalLookup.boardableAt(RegionalBusProvider.GG, "234001243", "101000002", anchor))
                    .willReturn(new RegionalArrivalLookup.Lookup(NOW.plusSeconds(420), null));

            TransferTargetArrival result = predictor.predictBoarding(leg, anchor, NOW);

            assertThat(result.arrival().arrivalAt()).isEqualTo(NOW.plusSeconds(420));
            assertThat(result.arrival().source()).isEqualTo(CurrentLegArrival.Source.REALTIME_ARRIVAL);
            assertThat(result.arrival().quality()).isEqualTo(CurrentLegArrival.Quality.APPROXIMATE);
            verifyNoInteractions(predictTransferUseCase);
        }

        @Test
        @DisplayName("TAGO 노선: 초 단위라 품질이 좋다")
        void tagoTargetIsGoodQuality() {
            JourneyLeg leg = regionalWaitLeg("BSB5202029000", "BSB186450201");
            Instant anchor = NOW.plusSeconds(60);
            given(regionalArrivalLookup.boardableAt(RegionalBusProvider.TAGO, "BSB5202029000", "BSB186450201", anchor))
                    .willReturn(new RegionalArrivalLookup.Lookup(NOW.plusSeconds(90), null));

            assertThat(predictor.predictBoarding(leg, anchor, NOW).arrival().quality())
                    .isEqualTo(CurrentLegArrival.Quality.GOOD);
        }

        @Test
        @DisplayName("지역 노선: 실시간 시야 밖이면 몫을 쓰지 않고 모른다")
        void regionalBeyondWindowSkipsTheCall() {
            JourneyLeg leg = regionalWaitLeg("234001243", "101000002");

            TransferTargetArrival result = predictor.predictBoarding(
                    leg, NOW.plus(properties.busRealtimeWindow()).plusSeconds(1), NOW);

            assertThat(result.arrival().arrivalAt()).isNull();
            assertThat(result.upstreamBlocked()).isFalse();
            verifyNoInteractions(regionalArrivalLookup);
        }

        @Test
        @DisplayName("지역 노선: 몫 소진만 막힘이고, 탈 차 없음과 조회 실패는 모름이다")
        void regionalFailures() {
            JourneyLeg leg = regionalWaitLeg("234001243", "101000002");
            Instant anchor = NOW.plusSeconds(60);
            given(regionalArrivalLookup.boardableAt(any(), anyString(), anyString(), any()))
                    .willReturn(RegionalArrivalLookup.Lookup.QUOTA,
                            RegionalArrivalLookup.Lookup.NO_REALTIME,
                            RegionalArrivalLookup.Lookup.BLOCKED);

            assertThat(predictor.predictBoarding(leg, anchor, NOW).upstreamBlocked()).isTrue();
            assertThat(predictor.predictBoarding(leg, anchor, NOW).upstreamBlocked()).isFalse();
            TransferTargetArrival failed = predictor.predictBoarding(leg, anchor, NOW);
            assertThat(failed.upstreamBlocked()).isFalse();
            assertThat(failed.arrival().arrivalAt()).isNull();
        }

        @Test
        @DisplayName("시외버스처럼 모양을 모르는 원천 노선은 지역 실시간에 묻지 않는다")
        void unknownSourceRouteIsNotRegional() {
            JourneyLeg leg = regionalWaitLeg(null, "1:BS_ACC3_33020010");

            assertThat(predictor.predictBoarding(leg, NOW.plusSeconds(60), NOW).arrival().arrivalAt()).isNull();

            verifyNoInteractions(regionalArrivalLookup);
        }

        @Test
        @DisplayName("사용자 준비 시각을 그대로 기준으로 넘긴다")
        void passesAnchorThrough() {
            Instant anchor = NOW.plusSeconds(420);
            givenPrediction(TransferArrival.Source.REALTIME, TransferArrival.Status.AVAILABLE,
                    anchor.plusSeconds(60), null);

            predictor.predictBoarding(waitLeg, anchor, NOW);

            ArgumentCaptor<Instant> captured = ArgumentCaptor.forClass(Instant.class);
            verify(predictTransferUseCase).predict(eq("ST-G"), eq("BL-721"), eq(3),
                    captured.capture(), any(), anyLong());
            assertThat(captured.getValue()).isEqualTo(anchor);
        }

        @Test
        @DisplayName("아직 시작하지 않은 단계여도 예측한다 — 그것이 이 예측기의 존재 이유다")
        void predictsBeforeLegStarts() {
            givenPrediction(TransferArrival.Source.REALTIME, TransferArrival.Status.AVAILABLE,
                    NOW.plusSeconds(480), null);

            TransferTargetArrival result = predictor.predictBoarding(waitLeg, NOW.plusSeconds(420), NOW);

            assertThat(waitLeg.getState()).isEqualTo(JourneyLegState.PENDING);
            assertThat(result.arrival().arrivalAt()).isEqualTo(NOW.plusSeconds(480));
        }

        @Test
        @DisplayName("실시간으로 잡으면 출처가 실시간이다")
        void realtimeSource() {
            givenPrediction(TransferArrival.Source.REALTIME, TransferArrival.Status.AVAILABLE,
                    NOW.plusSeconds(480), null);

            TransferTargetArrival result = predictor.predictBoarding(waitLeg, NOW.plusSeconds(420), NOW);

            assertThat(result.arrival().source())
                    .isEqualTo(CurrentLegArrival.Source.REALTIME_ARRIVAL);
            assertThat(result.arrival().quality()).isEqualTo(CurrentLegArrival.Quality.GOOD);
            assertThat(result.modelVersion()).isNull();
        }

        @Test
        @DisplayName("실시간으로 못 잡으면 모델 출처와 모델 이름을 남긴다")
        void modelSource() {
            givenPrediction(TransferArrival.Source.MODEL, TransferArrival.Status.AVAILABLE,
                    NOW.plusSeconds(500), "bus-eta-v3");

            TransferTargetArrival result = predictor.predictBoarding(waitLeg, NOW.plusSeconds(420), NOW);

            assertThat(result.arrival().source()).isEqualTo(CurrentLegArrival.Source.MODEL_ARRIVAL);
            assertThat(result.arrival().quality()).isEqualTo(CurrentLegArrival.Quality.APPROXIMATE);
            assertThat(result.modelVersion()).isEqualTo("bus-eta-v3");
        }

        @Test
        @DisplayName("시야 안이면 실시간까지 본다")
        void withinWindowUsesRealtime() {
            givenPrediction(TransferArrival.Source.REALTIME, TransferArrival.Status.AVAILABLE,
                    NOW.plusSeconds(480), null);

            predictor.predictBoarding(waitLeg, NOW.plusSeconds(420), NOW);

            verify(predictTransferUseCase).predict(anyString(), anyString(), anyInt(), any(),
                    eq(PredictTransferUseCase.Mode.REALTIME_THEN_MODEL), anyLong());
        }

        @Test
        @DisplayName("시야 밖이면 실시간을 건너뛰고 모델만 쓴다 — 호출은 여전히 한다")
        void beyondWindowUsesModelOnly() {
            Instant farAnchor = NOW.plusSeconds(properties.getBusRealtimeWindowSeconds() + 60);
            givenPrediction(TransferArrival.Source.MODEL, TransferArrival.Status.AVAILABLE,
                    farAnchor.plusSeconds(120), "bus-eta-v3");

            TransferTargetArrival result = predictor.predictBoarding(waitLeg, farAnchor, NOW);

            verify(predictTransferUseCase).predict(anyString(), anyString(), anyInt(), any(),
                    eq(PredictTransferUseCase.Mode.MODEL_ONLY), anyLong());
            assertThat(result.arrival().source()).isEqualTo(CurrentLegArrival.Source.MODEL_ARRIVAL);
        }

        @Test
        @DisplayName("시야 밖이고 모델도 못 답하면 모름이다")
        void beyondWindowWithoutModelAnswerIsUnknown() {
            Instant farAnchor = NOW.plusSeconds(properties.getBusRealtimeWindowSeconds() + 600);
            givenPrediction(TransferArrival.Source.MODEL, TransferArrival.Status.NO_VEHICLE, null, null);

            TransferTargetArrival result = predictor.predictBoarding(waitLeg, farAnchor, NOW);

            assertThat(result.arrival().quality()).isEqualTo(CurrentLegArrival.Quality.UNKNOWN);
            assertThat(result.upstreamBlocked()).isFalse();
        }

        @Test
        @DisplayName("앵커가 없으면 지금 시각으로 대신하지 않고 모른다고 답한다")
        void noAnchorMeansUnknown() {
            TransferTargetArrival result = predictor.predictBoarding(waitLeg, null, NOW);

            assertThat(result.arrival().quality()).isEqualTo(CurrentLegArrival.Quality.UNKNOWN);
            verifyNoInteractions(predictTransferUseCase);
        }

        @Test
        @DisplayName("앵커가 이미 지났어도 묻는다 — 그때는 다음 차량이 답이다")
        void pastAnchorStillAsks() {
            Instant pastAnchor = NOW.minusSeconds(120);
            givenPrediction(TransferArrival.Source.REALTIME, TransferArrival.Status.AVAILABLE,
                    NOW.plusSeconds(90), null);

            TransferTargetArrival result = predictor.predictBoarding(waitLeg, pastAnchor, NOW);

            verify(predictTransferUseCase).predict(anyString(), anyString(), anyInt(),
                    eq(pastAnchor), any(), anyLong());
            assertThat(result.arrival().arrivalAt()).isEqualTo(NOW.plusSeconds(90));
        }

        @Test
        @DisplayName("provider 가 막혔으면 근거 없음과 구분해 표시한다")
        void marksBlocked() {
            givenPrediction(TransferArrival.Source.NONE, TransferArrival.Status.BLOCKED, null, null);

            TransferTargetArrival result = predictor.predictBoarding(waitLeg, NOW.plusSeconds(420), NOW);

            assertThat(result.upstreamBlocked()).isTrue();
        }

        @Test
        @DisplayName("차량이 없는 것은 막힌 것이 아니다")
        void noVehicleIsNotBlocked() {
            givenPrediction(TransferArrival.Source.MODEL, TransferArrival.Status.NO_VEHICLE, null, null);

            TransferTargetArrival result = predictor.predictBoarding(waitLeg, NOW.plusSeconds(420), NOW);

            assertThat(result.upstreamBlocked()).isFalse();
            assertThat(result.arrival().quality()).isEqualTo(CurrentLegArrival.Quality.UNKNOWN);
        }

        @Test
        @DisplayName("못 탈 차량이어도 예측 시각을 그대로 돌려준다 — 걸러내면 환승 놓침이 모름으로 바뀐다")
        void unboardablePredictionIsPassedThrough() {
            Instant anchor = NOW.plusSeconds(420);
            // 사용자가 준비를 마치기 전에 지나갈 차량. 그 음수 여유가 MISSED 판정의 근거다.
            givenUnboardablePrediction(anchor.minusSeconds(120));

            TransferTargetArrival result = predictor.predictBoarding(waitLeg, anchor, NOW);

            assertThat(result.arrival().arrivalAt()).isEqualTo(anchor.minusSeconds(120));
            assertThat(result.arrival().quality()).isNotEqualTo(CurrentLegArrival.Quality.UNKNOWN);
        }

        @Test
        @DisplayName("정류장이 미해석이면 외부 예측을 한 번도 부르지 않는다")
        void unresolvedStopSkipsExternalCall() {
            JourneyLeg unresolved = legWithoutStop(JourneyLegType.WAIT_BUS);

            TransferTargetArrival result = predictor.predictBoarding(unresolved, NOW.plusSeconds(420), NOW);

            assertThat(result.arrival().quality()).isEqualTo(CurrentLegArrival.Quality.UNKNOWN);
            verifyNoInteractions(predictTransferUseCase);
        }

        @Test
        @DisplayName("외부 호출이 터지면 삼키지 않고 그대로 올린다")
        void propagatesUpstreamFailure() {
            given(predictTransferUseCase.predict(anyString(), anyString(), anyInt(), any(), any(), anyLong()))
                    .willThrow(new IllegalStateException("boom"));

            assertThatThrownBy(() -> predictor.predictBoarding(waitLeg, NOW.plusSeconds(420), NOW))
                    .isInstanceOf(IllegalStateException.class);
        }

        private void givenUnboardablePrediction(Instant predictedArrivalAt) {
            TransferPredictionResult result = new TransferPredictionResult(
                    "ST-G", "BL-721", 3, TransferArrival.Source.REALTIME,
                    TransferArrival.Status.AVAILABLE, NOW, NOW,
                    predictedArrivalAt, null, false, "vehicle-1", null);
            given(predictTransferUseCase.predict(anyString(), anyString(), anyInt(), any(), any(), anyLong()))
                    .willReturn(result);
        }

        private void givenPrediction(TransferArrival.Source source, TransferArrival.Status status,
                                     Instant predictedArrivalAt, String modelVersion) {
            TransferPredictionResult result = predictedArrivalAt == null
                    ? TransferPredictionResult.noResult("ST-G", "BL-721", 3, source, status, NOW, NOW)
                    : new TransferPredictionResult("ST-G", "BL-721", 3, source, status, NOW, NOW,
                            predictedArrivalAt, null, true, "vehicle-1", modelVersion);
            given(predictTransferUseCase.predict(anyString(), anyString(), anyInt(), any(), any(), anyLong()))
                    .willReturn(result);
        }
    }

    @Nested
    @DisplayName("지하철")
    class Subway {

        private final SubwayRealtimeArrivalReader arrivalReader = mock(SubwayRealtimeArrivalReader.class);
        private SubwayTransferTargetArrivalPredictor predictor;
        private JourneyLeg waitLeg;

        @BeforeEach
        void setUp() {
            predictor = new SubwayTransferTargetArrivalPredictor(
                    new SubwayBoardingTimeEstimator(arrivalReader,
                            new watoo.grd.nextroute.application.journey.observability.JourneyEvaluationMetrics(
                                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                            mock(watoo.grd.nextroute.application.subway.service.SubwayLineOrder.class)),
                    routeOptionRepository);
            waitLeg = waitLeg(JourneyLegType.WAIT_SUBWAY, JourneyLegState.PENDING);
            ReflectionTestUtils.setField(waitLeg, "routeId", "1002");
            given(routeOptionRepository.findById(OPTION_ID)).willReturn(Optional.of(subwayOption()));
        }

        @Test
        @DisplayName("준비 시각 이후에 오는 첫 열차를 고른다")
        void picksFirstBoardableTrain() {
            given(arrivalReader.arrivalsByStation(any(), any(), any(), anyInt()))
                    .willReturn(List.of(arrival(60), arrival(240), arrival(420)));

            TransferTargetArrival result = predictor.predictBoarding(waitLeg, NOW.plusSeconds(180), NOW);

            // 60초 뒤 열차는 사용자가 준비를 마치기 전에 지나간다.
            assertThat(result.arrival().arrivalAt()).isEqualTo(NOW.plusSeconds(240));
            assertThat(result.arrival().source()).isEqualTo(CurrentLegArrival.Source.REALTIME_ARRIVAL);
        }

        @Test
        @DisplayName("앵커가 멀어도 조회한다 — 지하철 실시간은 호출량 제한이 없다")
        void alwaysAsksRegardlessOfDistance() {
            Instant farAnchor = NOW.plusSeconds(3600);
            given(arrivalReader.arrivalsByStation(any(), any(), any(), anyInt()))
                    .willReturn(List.of(arrival(120)));

            predictor.predictBoarding(waitLeg, farAnchor, NOW);

            verify(arrivalReader).arrivalsByStation(any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("탈 수 있는 열차가 목록에 없으면 모름이다 — 못 탈 열차를 답으로 주지 않는다")
        void noBoardableTrainIsUnknown() {
            given(arrivalReader.arrivalsByStation(any(), any(), any(), anyInt()))
                    .willReturn(List.of(arrival(60), arrival(120)));

            TransferTargetArrival result = predictor.predictBoarding(waitLeg, NOW.plusSeconds(1800), NOW);

            // 지금 들어오는 열차를 답으로 주면 여유가 음수가 되어 "환승 놓침"이 뜬다.
            assertThat(result.arrival().quality()).isEqualTo(CurrentLegArrival.Quality.UNKNOWN);
        }

        @Test
        @DisplayName("역이 미해석이면 실시간 조회를 한 번도 부르지 않는다")
        void unresolvedStationSkipsExternalCall() {
            JourneyLeg unresolved = legWithoutStop(JourneyLegType.WAIT_SUBWAY);

            TransferTargetArrival result = predictor.predictBoarding(unresolved, NOW.plusSeconds(420), NOW);

            assertThat(result.arrival().quality()).isEqualTo(CurrentLegArrival.Quality.UNKNOWN);
            verifyNoInteractions(arrivalReader);
        }

        @Test
        @DisplayName("실시간 조회가 터지면 삼키지 않고 그대로 올린다")
        void propagatesUpstreamFailure() {
            given(arrivalReader.arrivalsByStation(any(), any(), any(), anyInt()))
                    .willThrow(new IllegalStateException("boom"));

            assertThatThrownBy(() -> predictor.predictBoarding(waitLeg, NOW.plusSeconds(180), NOW))
                    .isInstanceOf(IllegalStateException.class);
        }

        private SubwayRealtimeArrivalReader.Arrival arrival(int seconds) {
            return new SubwayRealtimeArrivalReader.Arrival(null, seconds);
        }
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private JourneyLeg waitLeg(JourneyLegType type, JourneyLegState state) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(7L)
                .routeRevision(1)
                .legIndex(2)
                .type(type)
                .state(state)
                .startStopId("ST-G")
                .startName("정류장G")
                .build();
        ReflectionTestUtils.setField(leg, "selectedRouteOptionId", OPTION_ID);
        return leg;
    }

    /** 정류장·역이 아직 내부 ID 로 해석되지 않은 단계. 이런 단계로 외부 호출이 나가면 안 된다. */
    private JourneyLeg legWithoutStop(JourneyLegType type) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(7L)
                .routeRevision(1)
                .legIndex(2)
                .type(type)
                .state(JourneyLegState.PENDING)
                .startName("정류장G")
                .build();
        ReflectionTestUtils.setField(leg, "selectedRouteOptionId", OPTION_ID);
        return leg;
    }

    /** 실제 엔티티로 만든다. 값만 필요한 객체를 가짜로 두면 중첩 stubbing 이 되어 시험이 깨진다. */
    private JourneyLegRouteOption busOption() {
        return JourneyLegRouteOption.builder()
                .journeyLegId(11L).optionIndex(0).laneIndex(0)
                .routeId("BL-721").routeName("721").boardingSeq(3)
                .build();
    }

    private JourneyLegRouteOption subwayOption() {
        return JourneyLegRouteOption.builder()
                .journeyLegId(11L).optionIndex(0).laneIndex(0)
                .routeId("1002").routeName("2호선").wayCode(2)
                .build();
    }
}
