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
import static org.mockito.ArgumentMatchers.any;
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
        private BusTransferTargetArrivalPredictor predictor;
        private JourneyLeg waitLeg;

        @BeforeEach
        void setUp() {
            predictor = new BusTransferTargetArrivalPredictor(
                    predictTransferUseCase, routeOptionRepository, properties);
            waitLeg = waitLeg(JourneyLegType.WAIT_BUS, JourneyLegState.PENDING);
            given(routeOptionRepository.findById(OPTION_ID)).willReturn(Optional.of(busOption()));
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
                    captured.capture());
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
        @DisplayName("앵커가 시야 밖이면 외부 API 를 부르지 않는다")
        void skipsCallBeyondHorizon() {
            Instant farAnchor = NOW.plusSeconds(properties.getTransferRealtimeHorizonSeconds() + 60);

            TransferTargetArrival result = predictor.predictBoarding(waitLeg, farAnchor, NOW);

            assertThat(result.arrival().quality()).isEqualTo(CurrentLegArrival.Quality.UNKNOWN);
            verifyNoInteractions(predictTransferUseCase);
        }

        @Test
        @DisplayName("앵커가 없으면 지금 시각으로 대신하지 않고 모른다고 답한다")
        void noAnchorMeansUnknown() {
            TransferTargetArrival result = predictor.predictBoarding(waitLeg, null, NOW);

            assertThat(result.arrival().quality()).isEqualTo(CurrentLegArrival.Quality.UNKNOWN);
            verify(predictTransferUseCase, never()).predict(anyString(), anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("앵커가 이미 지났어도 묻는다 — 그때는 다음 차량이 답이다")
        void pastAnchorStillAsks() {
            Instant pastAnchor = NOW.minusSeconds(120);
            givenPrediction(TransferArrival.Source.REALTIME, TransferArrival.Status.AVAILABLE,
                    NOW.plusSeconds(90), null);

            TransferTargetArrival result = predictor.predictBoarding(waitLeg, pastAnchor, NOW);

            verify(predictTransferUseCase).predict(anyString(), anyString(), anyInt(), eq(pastAnchor));
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

        private void givenPrediction(TransferArrival.Source source, TransferArrival.Status status,
                                     Instant predictedArrivalAt, String modelVersion) {
            TransferPredictionResult result = predictedArrivalAt == null
                    ? TransferPredictionResult.noResult("ST-G", "BL-721", 3, source, status, NOW, NOW)
                    : new TransferPredictionResult("ST-G", "BL-721", 3, source, status, NOW, NOW,
                            predictedArrivalAt, null, true, "vehicle-1", modelVersion);
            given(predictTransferUseCase.predict(anyString(), anyString(), anyInt(), any()))
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
                    arrivalReader, routeOptionRepository, properties);
            waitLeg = waitLeg(JourneyLegType.WAIT_SUBWAY, JourneyLegState.PENDING);
            ReflectionTestUtils.setField(waitLeg, "routeId", "1002");
            given(routeOptionRepository.findById(OPTION_ID)).willReturn(Optional.of(subwayOption()));
        }

        @Test
        @DisplayName("앵커가 시야 안이면 지금 들어오는 열차를 쓴다")
        void usesIncomingTrainWithinHorizon() {
            given(arrivalReader.arrivalsByStation(any(), any(), any(), anyInt()))
                    .willReturn(List.of(arrival(240)));

            TransferTargetArrival result = predictor.predictBoarding(waitLeg, NOW.plusSeconds(180), NOW);

            assertThat(result.arrival().arrivalAt()).isEqualTo(NOW.plusSeconds(240));
            assertThat(result.arrival().source()).isEqualTo(CurrentLegArrival.Source.REALTIME_ARRIVAL);
        }

        @Test
        @DisplayName("앵커가 시야 밖이면 조회하지 않는다 — 지하철은 모델도 없다")
        void skipsBeyondHorizon() {
            Instant farAnchor = NOW.plusSeconds(properties.getSubwayRealtimeHorizonSeconds() + 60);

            TransferTargetArrival result = predictor.predictBoarding(waitLeg, farAnchor, NOW);

            assertThat(result.arrival().quality()).isEqualTo(CurrentLegArrival.Quality.UNKNOWN);
            verifyNoInteractions(arrivalReader);
        }

        @Test
        @DisplayName("사용자가 도착하기 전에 지나갈 열차면 값은 주되 근사로 낮춘다")
        void trainBeforeUserIsApproximate() {
            given(arrivalReader.arrivalsByStation(any(), any(), any(), anyInt()))
                    .willReturn(List.of(arrival(60)));

            TransferTargetArrival result = predictor.predictBoarding(waitLeg, NOW.plusSeconds(300), NOW);

            assertThat(result.arrival().quality()).isEqualTo(CurrentLegArrival.Quality.APPROXIMATE);
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
