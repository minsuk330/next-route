package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import org.mockito.ArgumentCaptor;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.BoardingOutcome;
import watoo.grd.nextroute.application.journey.dto.BoardingQuery;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.observability.JourneyEvaluationMetrics;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 승차 시각 추정기. 저장된 단계가 아니라 <b>값</b>만 받아 답한다 — 재탐색 후보 경로는 DB 에 없다.
 *
 * <p>여기서 못박는 것 중 가장 중요한 하나: <b>못 타는 차를 추정기가 걸러내지 않는다.</b>
 * 여정 평가는 그 음수 여유로 "환승 놓침"을 판정하므로, 추정기가 걸러내면 그 감지가 죽는다.
 */
@DisplayName("승차 시각 추정")
class BoardingTimeEstimatorTest {

    private static final Instant NOW = Instant.parse("2026-08-04T09:00:00Z");

    @Nested
    @DisplayName("버스")
    class Bus {

        private final PredictTransferUseCase predictTransferUseCase = mock(PredictTransferUseCase.class);
        private final JourneyEvaluatorProperties properties = new JourneyEvaluatorProperties();
        private final MeterRegistry registry = new SimpleMeterRegistry();
        private final RegionalArrivalLookup regional = mock(RegionalArrivalLookup.class);
        private BusBoardingTimeEstimator estimator;

        @BeforeEach
        void setUp() {
            estimator = new BusBoardingTimeEstimator(
                    predictTransferUseCase, new TransferPredictionInterpreter(properties),
                    new JourneyEvaluationMetrics(registry), regional);
        }

        private double regionalMissCount(String reason) {
            var counter = registry.find(JourneyEvaluationMetrics.BOARDING_MISS)
                    .tag("mode", "regional_bus").tag("reason", reason).counter();
            return counter == null ? 0 : counter.count();
        }

        private BoardingQuery gyeonggi() {
            return new BoardingQuery(JourneyLegType.WAIT_BUS, "233001674", "센트럴파크", null, null, null, "234000068");
        }

        @Test
        @DisplayName("PR-1: 경기 노선(서울 노선·순번 없음)은 후보 칸 지역 조회로 승차 시각을 낸다 — 초 단위면 GOOD")
        void regionalBusUsesCandidateBucket() {
            org.mockito.BDDMockito.given(regional.boardableAt(RegionalBusProvider.GG, "234000068", "233001674",
                            NOW.plusSeconds(60), RegionalArrivalLookup.Bucket.CANDIDATE))
                    .willReturn(new RegionalArrivalLookup.Lookup(NOW.plusSeconds(300), null, true));

            BoardingOutcome outcome = estimator.estimate(gyeonggi(), NOW.plusSeconds(60), NOW, 2_000);

            assertThat(outcome.isPresent()).isTrue();
            assertThat(outcome.estimate().boardingAt()).isEqualTo(NOW.plusSeconds(300));
            assertThat(outcome.estimate().boardable()).isTrue();
            assertThat(outcome.estimate().source()).isEqualTo(CurrentLegArrival.Source.REALTIME_ARRIVAL);
            assertThat(outcome.estimate().quality()).isEqualTo(CurrentLegArrival.Quality.GOOD);
            org.mockito.Mockito.verifyNoInteractions(predictTransferUseCase);
        }

        @Test
        @DisplayName("PR-1: 분 단위뿐이면 APPROXIMATE")
        void regionalMinutesOnlyIsApproximate() {
            org.mockito.BDDMockito.given(regional.boardableAt(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .willReturn(new RegionalArrivalLookup.Lookup(NOW.plusSeconds(300), null, false));

            assertThat(estimator.estimate(gyeonggi(), NOW.plusSeconds(60), NOW, 2_000).estimate().quality())
                    .isEqualTo(CurrentLegArrival.Quality.APPROXIMATE);
        }

        @Test
        @DisplayName("PR-1: 지역 몫 소진은 막힘, 조회 실패·탈 차 없음은 근거 없음 — 사유가 regional_bus 로 남는다")
        void regionalFailures() {
            org.mockito.BDDMockito.given(regional.boardableAt(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .willReturn(new RegionalArrivalLookup.Lookup(null, "quota-exhausted"),
                            new RegionalArrivalLookup.Lookup(null, "arrival-blocked"),
                            new RegionalArrivalLookup.Lookup(null, "no-realtime"));

            assertThat(estimator.estimate(gyeonggi(), NOW.plusSeconds(60), NOW, 2_000).blocked()).isTrue();
            BoardingOutcome blocked = estimator.estimate(gyeonggi(), NOW.plusSeconds(60), NOW, 2_000);
            BoardingOutcome noRealtime = estimator.estimate(gyeonggi(), NOW.plusSeconds(60), NOW, 2_000);

            assertThat(blocked.isPresent()).isFalse();
            assertThat(blocked.blocked()).isFalse();
            assertThat(noRealtime.isPresent()).isFalse();
            assertThat(regionalMissCount("quota-exhausted")).isEqualTo(1);
            assertThat(regionalMissCount("no-realtime")).isEqualTo(1);
        }

        @Test
        @DisplayName("PR-1: 실시간 창 밖 앵커·남은 예산 1초 미만이면 지역 조회를 부르지 않는다")
        void regionalSkipsOutsideWindowAndLowBudget() {
            Instant farAnchor = NOW.plus(properties.busRealtimeWindow()).plusSeconds(600);

            assertThat(estimator.estimate(gyeonggi(), farAnchor, NOW, 2_000).isPresent()).isFalse();
            assertThat(estimator.estimate(gyeonggi(), NOW.plusSeconds(60), NOW, 500).isPresent()).isFalse();

            org.mockito.Mockito.verifyNoInteractions(regional);
            assertThat(regionalMissCount("outside-realtime-window")).isEqualTo(1);
            assertThat(regionalMissCount("budget-exhausted")).isEqualTo(1);
        }

        @Test
        @DisplayName("PR-1: 지역 노선 id 를 안 넘긴 질의(수락 재확인 등)·모양 모르는 노선(시외)은 지역 조회 없이 no_scope")
        void noRegionalWithoutSourceRouteOrUnknownShape() {
            BoardingQuery noSource = new BoardingQuery(JourneyLegType.WAIT_BUS, "233001674", "센트럴파크", null, null, null);
            BoardingQuery intercity = new BoardingQuery(JourneyLegType.WAIT_BUS, "1:BS_ACC3_33020010", "충주", null, null, null, null);

            assertThat(estimator.estimate(noSource, NOW.plusSeconds(60), NOW, 2_000).isPresent()).isFalse();
            assertThat(estimator.estimate(intercity, NOW.plusSeconds(60), NOW, 2_000).isPresent()).isFalse();

            org.mockito.Mockito.verifyNoInteractions(regional);
            assertThat(missCount("no_scope")).isEqualTo(2);
        }

        /** 못 구한 사유가 이 tag 로 남는지. 남지 않으면 운영에서 원인을 못 가린다. */
        private double missCount(String reason) {
            var counter = registry.find(JourneyEvaluationMetrics.BOARDING_MISS)
                    .tag("mode", "bus").tag("reason", reason).counter();
            return counter == null ? 0 : counter.count();
        }

        @Test
        @DisplayName("버스 대기 단계만 맡는다")
        void supportsWaitBusOnly() {
            assertThat(estimator.supports(JourneyLegType.WAIT_BUS)).isTrue();
            assertThat(estimator.supports(JourneyLegType.WAIT_SUBWAY)).isFalse();
        }

        @Test
        @DisplayName("앵커를 그대로 기준으로 넘기고 예산도 그대로 넘긴다")
        void passesAnchorAndBudgetThrough() {
            Instant anchor = NOW.plusSeconds(420);
            givenPrediction(TransferArrival.Source.REALTIME, TransferArrival.Status.AVAILABLE,
                    anchor.plusSeconds(60), true, null);

            estimator.estimate(query(), anchor, NOW, 1500L);

            ArgumentCaptor<Instant> captured = ArgumentCaptor.forClass(Instant.class);
            verify(predictTransferUseCase).predict(eq("ST-G"), eq("BL-721"), eq(3),
                    captured.capture(), any(), eq(1500L));
            assertThat(captured.getValue()).isEqualTo(anchor);
        }

        @Test
        @DisplayName("시야 안이면 실시간까지, 밖이면 모델만 본다")
        void modeFollowsAnchorDistance() {
            givenPrediction(TransferArrival.Source.REALTIME, TransferArrival.Status.AVAILABLE,
                    NOW.plusSeconds(480), true, null);

            estimator.estimate(query(), NOW.plusSeconds(420), NOW, 1000L);
            verify(predictTransferUseCase).predict(anyString(), anyString(), anyInt(), any(),
                    eq(PredictTransferUseCase.Mode.REALTIME_THEN_MODEL), anyLong());

            Instant farAnchor = NOW.plusSeconds(properties.getBusRealtimeWindowSeconds() + 60);
            estimator.estimate(query(), farAnchor, NOW, 1000L);
            verify(predictTransferUseCase).predict(anyString(), anyString(), anyInt(), any(),
                    eq(PredictTransferUseCase.Mode.MODEL_ONLY), anyLong());
        }

        @Test
        @DisplayName("실시간이면 출처가 실시간이고 신뢰도가 GOOD 이다")
        void realtimeMapping() {
            givenPrediction(TransferArrival.Source.REALTIME, TransferArrival.Status.AVAILABLE,
                    NOW.plusSeconds(480), true, null);

            BoardingOutcome outcome = estimator.estimate(query(), NOW.plusSeconds(420), NOW, 1000L);

            assertThat(outcome.isPresent()).isTrue();
            assertThat(outcome.estimate().boardingAt()).isEqualTo(NOW.plusSeconds(480));
            assertThat(outcome.estimate().source()).isEqualTo(CurrentLegArrival.Source.REALTIME_ARRIVAL);
            assertThat(outcome.estimate().quality()).isEqualTo(CurrentLegArrival.Quality.GOOD);
            assertThat(outcome.estimate().observedAt()).isEqualTo(NOW);
            assertThat(outcome.estimate().modelVersion()).isNull();
        }

        @Test
        @DisplayName("모델이 추정하면 출처와 모델 이름을 남기고 신뢰도를 낮춘다")
        void modelMapping() {
            givenPrediction(TransferArrival.Source.MODEL, TransferArrival.Status.AVAILABLE,
                    NOW.plusSeconds(500), true, "bus-eta-v3");

            BoardingOutcome outcome = estimator.estimate(query(), NOW.plusSeconds(420), NOW, 1000L);

            assertThat(outcome.estimate().source()).isEqualTo(CurrentLegArrival.Source.MODEL_ARRIVAL);
            assertThat(outcome.estimate().quality()).isEqualTo(CurrentLegArrival.Quality.APPROXIMATE);
            assertThat(outcome.estimate().modelVersion()).isEqualTo("bus-eta-v3");
        }

        @Test
        @DisplayName("못 탈 차량도 걸러내지 않고 그대로 싣는다 — 걸러내면 환승 놓침 감지가 죽는다")
        void doesNotFilterUnboardable() {
            Instant anchor = NOW.plusSeconds(420);
            givenPrediction(TransferArrival.Source.REALTIME, TransferArrival.Status.AVAILABLE,
                    anchor.minusSeconds(120), false, null);

            BoardingOutcome outcome = estimator.estimate(query(), anchor, NOW, 1000L);

            assertThat(outcome.isPresent()).isTrue();
            assertThat(outcome.estimate().boardable()).isFalse();
            assertThat(outcome.estimate().boardingAt()).isEqualTo(anchor.minusSeconds(120));
        }

        @Test
        @DisplayName("boardable 이 null 이면 못 타는 것으로 좁힌다")
        void nullBoardableIsFalse() {
            TransferPredictionResult result = new TransferPredictionResult(
                    "ST-G", "BL-721", 3, TransferArrival.Source.MODEL, TransferArrival.Status.AVAILABLE,
                    NOW, NOW, NOW.plusSeconds(480), null, null, null, "bus-eta-v3");
            given(predictTransferUseCase.predict(anyString(), anyString(), anyInt(), any(), any(), anyLong()))
                    .willReturn(result);

            BoardingOutcome outcome = estimator.estimate(query(), NOW.plusSeconds(420), NOW, 1000L);

            assertThat(outcome.estimate().boardable()).isFalse();
        }

        @Test
        @DisplayName("막힌 것과 근거가 없는 것을 가른다")
        void separatesBlockedFromUnknown() {
            givenPrediction(TransferArrival.Source.NONE, TransferArrival.Status.BLOCKED, null, false, null);
            BoardingOutcome blocked = estimator.estimate(query(), NOW.plusSeconds(420), NOW, 1000L);
            assertThat(blocked.isPresent()).isFalse();
            assertThat(blocked.blocked()).isTrue();

            givenPrediction(TransferArrival.Source.MODEL, TransferArrival.Status.NO_VEHICLE, null, false, null);
            BoardingOutcome none = estimator.estimate(query(), NOW.plusSeconds(420), NOW, 1000L);
            assertThat(none.isPresent()).isFalse();
            assertThat(none.blocked()).isFalse();
        }

        @Test
        @DisplayName("정류장·노선·순번이 비면 외부 호출 없이 모름이다")
        void unresolvedScopeSkipsExternalCall() {
            assertThat(estimator.estimate(
                    new BoardingQuery(JourneyLegType.WAIT_BUS, null, "정류장G", "BL-721", 3, null),
                    NOW.plusSeconds(420), NOW, 1000L).isPresent()).isFalse();
            assertThat(estimator.estimate(
                    new BoardingQuery(JourneyLegType.WAIT_BUS, "ST-G", "정류장G", " ", 3, null),
                    NOW.plusSeconds(420), NOW, 1000L).isPresent()).isFalse();
            assertThat(estimator.estimate(
                    new BoardingQuery(JourneyLegType.WAIT_BUS, "ST-G", "정류장G", "BL-721", null, null),
                    NOW.plusSeconds(420), NOW, 1000L).isPresent()).isFalse();

            verifyNoInteractions(predictTransferUseCase);
        }

        @Test
        @DisplayName("앵커가 없으면 외부 호출 없이 모름이다 — 지금 시각으로 대신하지 않는다")
        void noAnchorSkipsExternalCall() {
            assertThat(estimator.estimate(query(), null, NOW, 1000L).isPresent()).isFalse();
            verifyNoInteractions(predictTransferUseCase);
        }

        @Test
        @DisplayName("외부 호출이 터지면 삼키지 않고 그대로 올린다")
        void propagatesUpstreamFailure() {
            given(predictTransferUseCase.predict(anyString(), anyString(), anyInt(), any(), any(), anyLong()))
                    .willThrow(new IllegalStateException("boom"));

            assertThatThrownBy(() -> estimator.estimate(query(), NOW.plusSeconds(420), NOW, 1000L))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("못 구한 사유가 tag 로 남는다 — source=none 하나로는 원인을 못 가린다")
        void recordsMissReason() {
            givenPrediction(TransferArrival.Source.MODEL, TransferArrival.Status.NO_VEHICLE, null, false, null);
            estimator.estimate(query(), NOW.plusSeconds(420), NOW, 1000L);
            assertThat(missCount("no_vehicle")).isEqualTo(1);

            givenPrediction(TransferArrival.Source.NONE, TransferArrival.Status.LIMITED, null, false, null);
            estimator.estimate(query(), NOW.plusSeconds(420), NOW, 1000L);
            assertThat(missCount("limited")).isEqualTo(1);

            // 물어보기 전에 끝난 두 경우도 서로 다른 사유로 남는다.
            estimator.estimate(query(), null, NOW, 1000L);
            assertThat(missCount("no_anchor")).isEqualTo(1);
            estimator.estimate(new BoardingQuery(JourneyLegType.WAIT_BUS, "ST-G", "정류장G", "BL-721", null, null),
                    NOW.plusSeconds(420), NOW, 1000L);
            assertThat(missCount("no_scope")).isEqualTo(1);
        }

        @Test
        @DisplayName("추정에 성공하면 사유를 세지 않는다")
        void successRecordsNoMiss() {
            givenPrediction(TransferArrival.Source.REALTIME, TransferArrival.Status.AVAILABLE,
                    NOW.plusSeconds(480), true, null);

            assertThat(estimator.estimate(query(), NOW.plusSeconds(420), NOW, 1000L).isPresent()).isTrue();
            assertThat(registry.find(JourneyEvaluationMetrics.BOARDING_MISS).counters()).isEmpty();
        }

        private BoardingQuery query() {
            return new BoardingQuery(JourneyLegType.WAIT_BUS, "ST-G", "정류장G", "BL-721", 3, null);
        }

        private void givenPrediction(TransferArrival.Source source, TransferArrival.Status status,
                                     Instant predictedArrivalAt, boolean boardable, String modelVersion) {
            TransferPredictionResult result = predictedArrivalAt == null
                    ? TransferPredictionResult.noResult("ST-G", "BL-721", 3, source, status, NOW, NOW)
                    : new TransferPredictionResult("ST-G", "BL-721", 3, source, status, NOW, NOW,
                            predictedArrivalAt, null, boardable, "vehicle-1", modelVersion);
            given(predictTransferUseCase.predict(anyString(), anyString(), anyInt(), any(), any(), anyLong()))
                    .willReturn(result);
        }
    }

    @Nested
    @DisplayName("지하철")
    class Subway {

        private final SubwayRealtimeArrivalReader arrivalReader = mock(SubwayRealtimeArrivalReader.class);
        private final MeterRegistry registry = new SimpleMeterRegistry();
        private final watoo.grd.nextroute.application.subway.service.SubwayLineOrder lineOrder =
                mock(watoo.grd.nextroute.application.subway.service.SubwayLineOrder.class);
        private final SubwayBoardingTimeEstimator estimator = new SubwayBoardingTimeEstimator(
                arrivalReader, new JourneyEvaluationMetrics(registry), lineOrder);

        /** 못 구한 사유가 이 tag 로 남는지. */
        private double missCount(String reason) {
            var counter = registry.find(JourneyEvaluationMetrics.BOARDING_MISS)
                    .tag("mode", "subway").tag("reason", reason).counter();
            return counter == null ? 0 : counter.count();
        }

        @Test
        @DisplayName("지하철 대기 단계만 맡는다")
        void supportsWaitSubwayOnly() {
            assertThat(estimator.supports(JourneyLegType.WAIT_SUBWAY)).isTrue();
            assertThat(estimator.supports(JourneyLegType.WAIT_BUS)).isFalse();
        }

        @Test
        @DisplayName("준비 시각 이후에 오는 첫 열차를 고른다")
        void picksFirstBoardableTrain() {
            given(arrivalReader.arrivalsByStation(any(), any(), any(), anyInt()))
                    .willReturn(List.of(arrival(60), arrival(240), arrival(420)));

            BoardingOutcome outcome = estimator.estimate(query(), NOW.plusSeconds(180), NOW, 0L);

            // 60초 뒤 열차는 사용자가 준비를 마치기 전에 지나간다.
            assertThat(outcome.estimate().boardingAt()).isEqualTo(NOW.plusSeconds(240));
            assertThat(outcome.estimate().boardable()).isTrue();
            assertThat(outcome.estimate().source()).isEqualTo(CurrentLegArrival.Source.REALTIME_ARRIVAL);
            assertThat(outcome.estimate().quality()).isEqualTo(CurrentLegArrival.Quality.GOOD);
            assertThat(outcome.estimate().observedAt()).isEqualTo(NOW);
            assertThat(outcome.estimate().modelVersion()).isNull();
        }

        @Test
        @DisplayName("남은 초를 모르는 열차는 세지 않는다")
        void ignoresArrivalsWithoutSeconds() {
            given(arrivalReader.arrivalsByStation(any(), any(), any(), anyInt()))
                    .willReturn(List.of(new SubwayRealtimeArrivalReader.Arrival(null, null), arrival(300)));

            BoardingOutcome outcome = estimator.estimate(query(), NOW.plusSeconds(180), NOW, 0L);

            assertThat(outcome.estimate().boardingAt()).isEqualTo(NOW.plusSeconds(300));
        }

        @Test
        @DisplayName("#480 남은 초가 없어도 '[N]번째 전역'이면 그 역 출발 → 이 역 도착 계획 초로 어림한다(APPROXIMATE)")
        void estimatesTrainWithoutSecondsFromStationsAway() {
            // 3호선(1003) wayCode 2 → 하행. 3번째 전역 출발부터 이 역까지 계획 240초.
            given(lineOrder.secondsFromBehind("1003", "하행", "ST-G", 3)).willReturn(java.util.Optional.of(240));
            given(arrivalReader.arrivalsByStation(any(), any(), any(), anyInt()))
                    .willReturn(List.of(coded("99", "[3]번째 전역 (역삼)"), arrival(420)));

            BoardingOutcome outcome = estimator.estimate(line3(), NOW.plusSeconds(180), NOW, 0L);

            assertThat(outcome.estimate().boardingAt()).isEqualTo(NOW.plusSeconds(240));
            assertThat(outcome.estimate().quality()).isEqualTo(CurrentLegArrival.Quality.APPROXIMATE);
            assertThat(outcome.estimate().source()).isEqualTo(CurrentLegArrival.Source.REALTIME_ARRIVAL);
        }

        @Test
        @DisplayName("#480 진입·도착(0 역)은 지금이다 — 원천이 사건으로 답했으니 GOOD")
        void enteringTrainWithoutSecondsIsNow() {
            given(arrivalReader.arrivalsByStation(any(), any(), any(), anyInt()))
                    .willReturn(List.of(coded("0", "정류장G 진입"), arrival(300)));

            BoardingOutcome outcome = estimator.estimate(line3(), NOW, NOW, 0L);

            assertThat(outcome.estimate().boardingAt()).isEqualTo(NOW);
            assertThat(outcome.estimate().quality()).isEqualTo(CurrentLegArrival.Quality.GOOD);
        }

        @Test
        @DisplayName("#480 남은 초가 있는 열차가 더 빠르면 그 열차다(GOOD). 거리도 모르는 열차·계획 초가 없는 열차는 세지 않는다")
        void secondsWinAndUnknownDistanceIsIgnored() {
            given(lineOrder.secondsFromBehind("1003", "하행", "ST-G", 5)).willReturn(java.util.Optional.of(600));
            given(lineOrder.secondsFromBehind("1003", "하행", "ST-G", 2)).willReturn(java.util.Optional.empty());
            given(arrivalReader.arrivalsByStation(any(), any(), any(), anyInt()))
                    .willReturn(List.of(coded("99", "[5]번째 전역"), coded("99", "[2]번째 전역"),
                            coded("99", "이상한 문구"), arrival(300)));

            BoardingOutcome outcome = estimator.estimate(line3(), NOW.plusSeconds(180), NOW, 0L);

            assertThat(outcome.estimate().boardingAt()).isEqualTo(NOW.plusSeconds(300));
            assertThat(outcome.estimate().quality()).isEqualTo(CurrentLegArrival.Quality.GOOD);
        }

        @Test
        @DisplayName("#480 남은 초 없는 열차만 있어 예전엔 '탈 열차 없음'이던 경우도 값이 나온다")
        void onlyTrainsWithoutSecondsStillGiveAnEstimate() {
            given(lineOrder.secondsFromBehind("1003", "하행", "ST-G", 1)).willReturn(java.util.Optional.of(150));
            given(arrivalReader.arrivalsByStation(any(), any(), any(), anyInt()))
                    .willReturn(List.of(coded("3", "전역 출발")));

            BoardingOutcome outcome = estimator.estimate(line3(), NOW.plusSeconds(60), NOW, 0L);

            assertThat(outcome.isPresent()).isTrue();
            assertThat(outcome.estimate().boardingAt()).isEqualTo(NOW.plusSeconds(150));
            assertThat(missCount("no_boardable_train")).isZero();
        }

        @Test
        @DisplayName("앵커가 멀어도 조회한다 — 호출량 제한이 없다")
        void alwaysAsksRegardlessOfDistance() {
            given(arrivalReader.arrivalsByStation(any(), any(), any(), anyInt()))
                    .willReturn(List.of(arrival(120)));

            estimator.estimate(query(), NOW.plusSeconds(3600), NOW, 0L);

            verify(arrivalReader).arrivalsByStation(eq("1002"), eq("ST-G"), eq("정류장G"), eq(2));
        }

        @Test
        @DisplayName("탈 수 있는 열차가 없으면 모름이다 — 시간표로 채우지 않는다")
        void noBoardableTrainIsNone() {
            given(arrivalReader.arrivalsByStation(any(), any(), any(), anyInt()))
                    .willReturn(List.of(arrival(60), arrival(120)));

            BoardingOutcome outcome = estimator.estimate(query(), NOW.plusSeconds(1800), NOW, 0L);

            assertThat(outcome.isPresent()).isFalse();
            assertThat(outcome.blocked()).isFalse();
        }

        @Test
        @DisplayName("방향·역·노선이 비면 조회 없이 모름이다")
        void unresolvedScopeSkipsExternalCall() {
            assertThat(estimator.estimate(
                    new BoardingQuery(JourneyLegType.WAIT_SUBWAY, "ST-G", "정류장G", "1002", null, null),
                    NOW.plusSeconds(180), NOW, 0L).isPresent()).isFalse();
            assertThat(estimator.estimate(
                    new BoardingQuery(JourneyLegType.WAIT_SUBWAY, null, "정류장G", "1002", null, 2),
                    NOW.plusSeconds(180), NOW, 0L).isPresent()).isFalse();
            assertThat(estimator.estimate(
                    new BoardingQuery(JourneyLegType.WAIT_SUBWAY, "ST-G", "정류장G", null, null, 2),
                    NOW.plusSeconds(180), NOW, 0L).isPresent()).isFalse();

            verifyNoInteractions(arrivalReader);
        }

        @Test
        @DisplayName("막힘 개념이 없다 — 답이 없어도 blocked 는 항상 false 다")
        void neverBlocked() {
            given(arrivalReader.arrivalsByStation(any(), any(), any(), anyInt())).willReturn(List.of());

            assertThat(estimator.estimate(query(), NOW.plusSeconds(180), NOW, 0L).blocked()).isFalse();
        }

        @Test
        @DisplayName("조회가 비어 온 것과 탈 열차가 없던 것을 다른 사유로 남긴다")
        void recordsMissReason() {
            given(arrivalReader.arrivalsByStation(any(), any(), any(), anyInt()))
                    .willReturn(List.of(arrival(60), arrival(120)));
            estimator.estimate(query(), NOW.plusSeconds(1800), NOW, 0L);
            assertThat(missCount("no_boardable_train")).isEqualTo(1);

            given(arrivalReader.arrivalsByStation(any(), any(), any(), anyInt())).willReturn(List.of());
            estimator.estimate(query(), NOW.plusSeconds(180), NOW, 0L);
            assertThat(missCount("no_arrival_rows")).isEqualTo(1);

            // 방향을 모르면 조회 자체를 안 한다 — 그 사유도 따로 남는다.
            estimator.estimate(new BoardingQuery(JourneyLegType.WAIT_SUBWAY, "ST-G", "정류장G", "1002", null, null),
                    NOW.plusSeconds(180), NOW, 0L);
            assertThat(missCount("no_way_code")).isEqualTo(1);
        }

        private BoardingQuery query() {
            return new BoardingQuery(JourneyLegType.WAIT_SUBWAY, "ST-G", "정류장G", "1002", null, 2);
        }

        private SubwayRealtimeArrivalReader.Arrival arrival(int seconds) {
            return new SubwayRealtimeArrivalReader.Arrival(null, seconds);
        }

        /** 순환선이 아닌 노선 — wayCode 2 가 하행이다(2호선은 0/1 이라 2 면 방향 모름). */
        private BoardingQuery line3() {
            return new BoardingQuery(JourneyLegType.WAIT_SUBWAY, "ST-G", "정류장G", "1003", null, 2);
        }

        private SubwayRealtimeArrivalReader.Arrival coded(String code, String message) {
            return new SubwayRealtimeArrivalReader.Arrival(
                    watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain.builder().trainNo("T" + code)
                            .arrivalCode(code).currentMessage(message).build(), null);
        }
    }
}
