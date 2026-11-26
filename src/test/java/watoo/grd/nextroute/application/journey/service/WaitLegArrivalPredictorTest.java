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
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
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
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("기다리는 단계 ETA 는 차량이 언제 오는지로 구한다")
class WaitLegArrivalPredictorTest {

    private static final Instant NOW = Instant.parse("2026-08-04T09:00:00Z");

    private final PredictTransferUseCase predictTransferUseCase = mock(PredictTransferUseCase.class);
    private final SubwayRealtimeArrivalReader arrivalReader = mock(SubwayRealtimeArrivalReader.class);
    private final JourneyLegRouteOptionRepository routeOptionRepository =
            mock(JourneyLegRouteOptionRepository.class);

    private final RegionalArrivalLookup regionalArrivalLookup = mock(RegionalArrivalLookup.class);

    private final WaitBusArrivalPredictor busPredictor =
            new WaitBusArrivalPredictor(predictTransferUseCase, routeOptionRepository, regionalArrivalLookup);
    private final watoo.grd.nextroute.application.regionalsubway.service.RegionalLegWaitReader regionalReader =
            mock(watoo.grd.nextroute.application.regionalsubway.service.RegionalLegWaitReader.class);
    private final WaitSubwayArrivalPredictor subwayPredictor =
            new WaitSubwayArrivalPredictor(arrivalReader, routeOptionRepository, regionalReader);

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
    @DisplayName("경기·지방 버스 대기 (#333)")
    class WaitRegionalBus {

        @Test
        @DisplayName("TAGO 노선은 TAGO 실시간의 가장 이른 도착을 쓴다")
        void tagoNextArrival() {
            JourneyLeg leg = regionalLeg("BSB186450201");
            givenOption(regionalOption("BSB5202029000"));
            given(regionalArrivalLookup.nextArrival(RegionalBusProvider.TAGO, "BSB5202029000", "BSB186450201"))
                    .willReturn(new RegionalArrivalLookup.Lookup(NOW.plusSeconds(180), null));

            CurrentLegArrival arrival = busPredictor.predict(leg, null, NOW);

            assertThat(arrival.arrivalAt()).isEqualTo(NOW.plusSeconds(180));
            assertThat(arrival.remainingSeconds()).isEqualTo(180);
            assertThat(arrival.source()).isEqualTo(Source.REALTIME_ARRIVAL);
            assertThat(arrival.quality()).isEqualTo(Quality.GOOD);
            verify(predictTransferUseCase, never()).predict(anyString(), anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("경기 GBIS 노선은 경기 실시간, 분 단위라 근사")
        void gyeonggiNextArrival() {
            JourneyLeg leg = regionalLeg("202000141");
            givenOption(regionalOption("233000031"));
            given(regionalArrivalLookup.nextArrival(RegionalBusProvider.GG, "233000031", "202000141"))
                    .willReturn(new RegionalArrivalLookup.Lookup(NOW.plusSeconds(300), null));

            assertThat(busPredictor.predict(leg, null, NOW).quality()).isEqualTo(Quality.APPROXIMATE);
        }

        @Test
        @DisplayName("지역 실시간이 답을 못 주면 모른다")
        void regionalNoRealtimeIsUnknown() {
            JourneyLeg leg = regionalLeg("202000141");
            givenOption(regionalOption("233000031"));
            given(regionalArrivalLookup.nextArrival(any(), anyString(), anyString()))
                    .willReturn(new RegionalArrivalLookup.Lookup(null, "no-realtime"));

            assertThat(busPredictor.predict(leg, null, NOW).isUnknown()).isTrue();
        }

        @Test
        @DisplayName("모양을 모르는 원천 노선 id(서울 DB 에 없는 서울 노선 등)는 지역 API 로 묻지 않는다")
        void unknownShapeIsNotRegional() {
            JourneyLeg leg = regionalLeg("ST-A");
            givenOption(regionalOption("100100118"));

            assertThat(busPredictor.predict(leg, null, NOW).isUnknown()).isTrue();
            verifyNoInteractions(regionalArrivalLookup);
        }

        @Test
        @DisplayName("PR-1: 저장된 실시간 정류장 id 가 있으면 원천(GTFS 원형일 수 있음) 대신 그것으로 묻는다")
        void usesStoredRealtimeStopId() {
            JourneyLeg leg = regionalLeg("1:BS_TAGO_GGB233001674");
            ReflectionTestUtils.setField(leg, "startStopId", "233001674");
            givenOption(regionalOption("234000068"));
            given(regionalArrivalLookup.nextArrival(RegionalBusProvider.GG, "234000068", "233001674"))
                    .willReturn(new RegionalArrivalLookup.Lookup(NOW.plusSeconds(240), null));

            assertThat(busPredictor.predict(leg, null, NOW).arrivalAt()).isEqualTo(NOW.plusSeconds(240));
        }

        private JourneyLeg regionalLeg(String sourceStopId) {
            JourneyLeg leg = leg(JourneyLegType.WAIT_BUS, JourneyLegState.ACTIVE, null, null);
            ReflectionTestUtils.setField(leg, "sourceStartStopId", sourceStopId);
            return leg;
        }

        private JourneyLegRouteOption regionalOption(String sourceRouteId) {
            JourneyLegRouteOption option = option(null, null, null);
            ReflectionTestUtils.setField(option, "sourceRouteId", sourceRouteId);
            return option;
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
            given(arrivalReader.arrivalsByStation("1009", "923", "강남", 1)).willReturn(List.of(
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
            verify(arrivalReader, never()).arrivalsByStation(anyString(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("맞는 열차가 없으면 정적 대기시간을 만들지 않는다")
        void noMatchingTrainIsUnknown() {
            JourneyLeg leg = leg(JourneyLegType.WAIT_SUBWAY, JourneyLegState.ACTIVE, "923", "1009");
            givenOption(option(null, null, 1));
            given(arrivalReader.arrivalsByStation("1009", "923", "강남", 1)).willReturn(List.of());

            assertThat(subwayPredictor.predict(leg, null, NOW).isUnknown()).isTrue();
        }

        @Test
        @DisplayName("실시간 조회가 빈 응답이면 모른다고 말한다")
        void emptyRealtimeResponseIsUnknown() {
            JourneyLeg leg = leg(JourneyLegType.WAIT_SUBWAY, JourneyLegState.ACTIVE, "923", "1009");
            givenOption(option(null, null, 1));
            given(arrivalReader.arrivalsByStation("1009", "923", "강남", 1)).willReturn(List.of());

            assertThat(subwayPredictor.predict(leg, null, NOW).isUnknown()).isTrue();
        }

        @Test
        @DisplayName("역 이름이 없으면 실시간 조회를 하지 않는다")
        void missingStationNameSkipsCall() {
            JourneyLeg leg = leg(JourneyLegType.WAIT_SUBWAY, JourneyLegState.ACTIVE, "923", "1009");
            ReflectionTestUtils.setField(leg, "startName", null);
            givenOption(option(null, null, 1));
            given(arrivalReader.arrivalsByStation("1009", "923", null, 1)).willReturn(List.of());

            assertThat(subwayPredictor.predict(leg, null, NOW).isUnknown()).isTrue();
        }

        @Test
        @DisplayName("남은 초를 모르는 열차는 건너뛴다")
        void trainWithoutSecondsIsSkipped() {
            JourneyLeg leg = leg(JourneyLegType.WAIT_SUBWAY, JourneyLegState.ACTIVE, "923", "1009");
            givenOption(option(null, null, 1));
            given(arrivalReader.arrivalsByStation("1009", "923", "강남", 1)).willReturn(List.of(
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
                .startName("강남")
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

    @org.junit.jupiter.api.Nested
    @DisplayName("지방 지하철(공식 시간표) — 서울 노선 id 가 없는 대기 단계만")
    class RegionalTimetable {

        @Test
        @DisplayName("서울 노선 id 가 없고 지방 시간표가 있으면 TIMETABLE·APPROXIMATE 로 다음 열차 시각을 준다")
        void regionalLegUsesTimetable() {
            JourneyLeg leg = leg(JourneyLegType.WAIT_SUBWAY, JourneyLegState.ACTIVE, null, null);
            java.time.Instant now = java.time.Instant.parse("2026-11-15T23:00:00Z");
            java.time.Instant dep = now.plusSeconds(210);
            org.mockito.BDDMockito.given(regionalReader.nextDeparture(leg, now)).willReturn(java.util.Optional.of(
                    new watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparturesView.Departure(dep, 3, "다대포해수욕장", 39)));

            CurrentLegArrival arrival = subwayPredictor.predict(leg, null, now);

            assertThat(arrival.arrivalAt()).isEqualTo(dep);
            assertThat(arrival.remainingSeconds()).isEqualTo(210);
            assertThat(arrival.source()).isEqualTo(CurrentLegArrival.Source.TIMETABLE);
            assertThat(arrival.quality()).isEqualTo(CurrentLegArrival.Quality.APPROXIMATE);
        }

        @Test
        @DisplayName("서울 노선(노선 id 있음)은 시간표 경로에 절대 가지 않는다 — 실시간이 없으면 모름 그대로")
        void seoulLegNeverUsesTimetable() {
            JourneyLeg leg = leg(JourneyLegType.WAIT_SUBWAY, JourneyLegState.ACTIVE, "923", "1009");
            subwayPredictor.predict(leg, null, java.time.Instant.parse("2026-11-15T23:00:00Z"));
            org.mockito.Mockito.verifyNoInteractions(regionalReader);
        }

        @Test
        @DisplayName("빈 노선 id(\"\")도 지방 경로 — hasSubwayRealtimeLine 과 같은 기준")
        void blankRouteIdIsRegional() {
            JourneyLeg leg = leg(JourneyLegType.WAIT_SUBWAY, JourneyLegState.ACTIVE, null, " ");
            subwayPredictor.predict(leg, null, java.time.Instant.parse("2026-11-15T23:00:00Z"));
            org.mockito.Mockito.verify(regionalReader).nextDeparture(org.mockito.ArgumentMatchers.eq(leg), org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verifyNoInteractions(arrivalReader);
        }

        @Test
        @DisplayName("지방 시간표가 없으면(서울 미해석 단계, serving off) 모름")
        void noTimetableIsUnknown() {
            JourneyLeg leg = leg(JourneyLegType.WAIT_SUBWAY, JourneyLegState.ACTIVE, null, null);
            assertThat(subwayPredictor.predict(leg, null, java.time.Instant.now()).isUnknown()).isTrue();
        }
    }
}
