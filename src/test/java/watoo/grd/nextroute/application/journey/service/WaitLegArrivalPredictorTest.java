package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Quality;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Source;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("기다리는 단계 ETA 는 차량이 언제 오는지로 구한다")
class WaitLegArrivalPredictorTest {

    private static final Instant NOW = Instant.parse("2026-08-04T09:00:00Z");

    private final PredictTransferUseCase predictTransferUseCase = mock(PredictTransferUseCase.class);
    private final SubwayRealtimeArrivalReader arrivalReader = mock(SubwayRealtimeArrivalReader.class);
    private final JourneyLegRouteOptionRepository routeOptionRepository =
            mock(JourneyLegRouteOptionRepository.class);

    private final WaitBusArrivalPredictor busPredictor =
            new WaitBusArrivalPredictor(predictTransferUseCase, routeOptionRepository);
    private final WaitSubwayArrivalPredictor subwayPredictor =
            new WaitSubwayArrivalPredictor(arrivalReader, routeOptionRepository);

    @Test
    @DisplayName("각자 자기 단계만 맡는다")
    void supportsOwnLegTypeOnly() {
        assertThat(busPredictor.supports(JourneyLegType.WAIT_BUS)).isTrue();
        assertThat(busPredictor.supports(JourneyLegType.WAIT_SUBWAY)).isFalse();
        assertThat(busPredictor.supports(JourneyLegType.RIDE_BUS)).isFalse();

        assertThat(subwayPredictor.supports(JourneyLegType.WAIT_SUBWAY)).isTrue();
        assertThat(subwayPredictor.supports(JourneyLegType.WAIT_BUS)).isFalse();
    }

    @Nested
    @DisplayName("버스 대기")
    class WaitBus {

        @Test
        @DisplayName("실시간으로 잡힌 차량이면 그 도착시각이 단계 종료 시각이다")
        void realtimeArrivalBecomesLegEnd() {
            JourneyLeg leg = leg(JourneyLegType.WAIT_BUS, JourneyLegState.ACTIVE, "ST-A", "R1");
            givenOption(option("R1", 10, null));
            given(predictTransferUseCase.predict("ST-A", "R1", 10, NOW)).willReturn(
                    TransferPredictionResult.available("ST-A", "R1", 10,
                            TransferArrival.Source.REALTIME, NOW, NOW,
                            NOW.plusSeconds(240), "veh-1", null));

            CurrentLegArrival arrival = busPredictor.predict(leg, null, NOW);

            assertThat(arrival.source()).isEqualTo(Source.REALTIME_ARRIVAL);
            assertThat(arrival.quality()).isEqualTo(Quality.GOOD);
            assertThat(arrival.arrivalAt()).isEqualTo(NOW.plusSeconds(240));
            assertThat(arrival.remainingSeconds()).isEqualTo(240);
        }

        @Test
        @DisplayName("모델 추정이면 값은 주되 품질을 낮춘다")
        void modelPredictionIsApproximate() {
            JourneyLeg leg = leg(JourneyLegType.WAIT_BUS, JourneyLegState.ACTIVE, "ST-A", "R1");
            givenOption(option("R1", 10, null));
            given(predictTransferUseCase.predict(anyString(), anyString(), anyInt(), any()))
                    .willReturn(TransferPredictionResult.available("ST-A", "R1", 10,
                            TransferArrival.Source.MODEL, NOW, NOW,
                            NOW.plusSeconds(300), "veh-2", "v1"));

            assertThat(busPredictor.predict(leg, null, NOW).quality())
                    .isEqualTo(Quality.APPROXIMATE);
        }

        @Test
        @DisplayName("실시간도 모델도 답을 못 주면 계획 소요시간으로 채우지 않고 모른다고 말한다")
        void noPredictionMeansUnknown() {
            JourneyLeg leg = leg(JourneyLegType.WAIT_BUS, JourneyLegState.ACTIVE, "ST-A", "R1");
            givenOption(option("R1", 10, null));
            given(predictTransferUseCase.predict(anyString(), anyString(), anyInt(), any()))
                    .willReturn(TransferPredictionResult.noResult("ST-A", "R1", 10,
                            TransferArrival.Source.NONE,
                            TransferArrival.Status.NO_VEHICLE, NOW, NOW));

            assertThat(busPredictor.predict(leg, null, NOW).isUnknown()).isTrue();
        }

        @Test
        @DisplayName("아직 시작하지 않은 단계는 조회하지 않는다")
        void pendingLegIsNotQueried() {
            JourneyLeg leg = leg(JourneyLegType.WAIT_BUS, JourneyLegState.PENDING, "ST-A", "R1");

            assertThat(busPredictor.predict(leg, null, NOW).isUnknown()).isTrue();
            verify(predictTransferUseCase, never()).predict(anyString(), anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("노선·정류장 순번이 해석되지 않았으면 조회하지 않는다")
        void unresolvedScopeIsNotQueried() {
            JourneyLeg leg = leg(JourneyLegType.WAIT_BUS, JourneyLegState.ACTIVE, "ST-A", "R1");
            givenOption(option("R1", null, null));

            assertThat(busPredictor.predict(leg, null, NOW).isUnknown()).isTrue();
            verify(predictTransferUseCase, never()).predict(anyString(), anyString(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("지하철 대기")
    class WaitSubway {

        @Test
        @DisplayName("가장 빨리 오는 열차로 단계 종료 시각을 잡는다")
        void earliestTrainBecomesLegEnd() {
            JourneyLeg leg = leg(JourneyLegType.WAIT_SUBWAY, JourneyLegState.ACTIVE, "923", "1009");
            givenOption(option(null, null, 1));
            given(arrivalReader.arrivals("1009", "923", 1)).willReturn(List.of(
                    arrival(180), arrival(420)));

            CurrentLegArrival result = subwayPredictor.predict(leg, null, NOW);

            assertThat(result.source()).isEqualTo(Source.REALTIME_ARRIVAL);
            assertThat(result.arrivalAt()).isEqualTo(NOW.plusSeconds(180));
        }

        @Test
        @DisplayName("방향을 모르면 반대 방향 열차를 집지 않고 모른다고 말한다")
        void unknownDirectionIsUnknown() {
            JourneyLeg leg = leg(JourneyLegType.WAIT_SUBWAY, JourneyLegState.ACTIVE, "923", "1009");
            givenOption(option(null, null, null));

            assertThat(subwayPredictor.predict(leg, null, NOW).isUnknown()).isTrue();
            verify(arrivalReader, never()).arrivals(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("맞는 열차가 없으면 정적 대기시간을 만들지 않는다")
        void noMatchingTrainIsUnknown() {
            JourneyLeg leg = leg(JourneyLegType.WAIT_SUBWAY, JourneyLegState.ACTIVE, "923", "1009");
            givenOption(option(null, null, 1));
            given(arrivalReader.arrivals("1009", "923", 1)).willReturn(List.of());

            assertThat(subwayPredictor.predict(leg, null, NOW).isUnknown()).isTrue();
        }

        @Test
        @DisplayName("남은 초를 모르는 열차는 건너뛴다")
        void trainWithoutSecondsIsSkipped() {
            JourneyLeg leg = leg(JourneyLegType.WAIT_SUBWAY, JourneyLegState.ACTIVE, "923", "1009");
            givenOption(option(null, null, 1));
            given(arrivalReader.arrivals("1009", "923", 1)).willReturn(List.of(
                    arrival(null), arrival(300)));

            assertThat(subwayPredictor.predict(leg, null, NOW).arrivalAt())
                    .isEqualTo(NOW.plusSeconds(300));
        }
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private void givenOption(JourneyLegRouteOption option) {
        given(routeOptionRepository.findById(900L)).willReturn(Optional.of(option));
    }

    private JourneyLeg leg(JourneyLegType type, JourneyLegState state,
                           String startStopId, String routeId) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(1L)
                .routeRevision(1)
                .legIndex(1)
                .type(type)
                .state(state)
                .startStopId(startStopId)
                .plannedDurationSec(600)
                .build();
        ReflectionTestUtils.setField(leg, "id", 10L);
        ReflectionTestUtils.setField(leg, "routeId", routeId);
        ReflectionTestUtils.setField(leg, "selectedRouteOptionId", 900L);
        return leg;
    }

    private JourneyLegRouteOption option(String routeId, Integer boardingSeq, Integer wayCode) {
        JourneyLegRouteOption option = JourneyLegRouteOption.builder()
                .journeyLegId(10L)
                .optionIndex(0)
                .laneIndex(0)
                .routeId(routeId)
                .boardingSeq(boardingSeq)
                .wayCode(wayCode)
                .build();
        ReflectionTestUtils.setField(option, "id", 900L);
        return option;
    }

    private SubwayRealtimeArrivalReader.Arrival arrival(Integer seconds) {
        return new SubwayRealtimeArrivalReader.Arrival(
                SubwayRealtimeTrain.builder().trainNo("T1").lineId("1009").stationId("923")
                        .direction("상행").arrivalSeconds(seconds).build(),
                seconds);
    }
}
