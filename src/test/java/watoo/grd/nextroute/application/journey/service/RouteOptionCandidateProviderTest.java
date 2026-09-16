package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteContext;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.application.route.routing.RideDurationResult;
import watoo.grd.nextroute.application.route.routing.RouteRideDurationPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 같은 정류장 다른 버스 후보의 계약.
 *
 * <p>이 시험의 무게중심은 <b>제안하지 말아야 할 때 제안하지 않는지</b>다. 잘못 올린 후보 하나는
 * 사용자를 실제로 다른 버스에 태우고, 그 버스가 더 늦으면 되돌릴 방법이 없다.
 */
@DisplayName("같은 정류장 다른 노선 후보")
class RouteOptionCandidateProviderTest {

    private static final Long JOURNEY_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");
    private static final Instant READY_AT = NOW.plusSeconds(300);
    private static final long SELECTED_OPTION_ID = 100L;

    private final TransferConnectionRepository transferRepository =
            mock(TransferConnectionRepository.class);
    private final JourneyLegRepository legRepository = mock(JourneyLegRepository.class);
    private final JourneyLegRouteOptionRepository routeOptionRepository =
            mock(JourneyLegRouteOptionRepository.class);
    private final PredictTransferUseCase predictTransferUseCase = mock(PredictTransferUseCase.class);
    private final RouteRideDurationPort rideDurationPort = mock(RouteRideDurationPort.class);
    private final JourneyRerouteProperties properties = new JourneyRerouteProperties();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final JourneyRerouteMetrics metrics = new JourneyRerouteMetrics(registry);

    /** 노선별로 무엇을 답할지. 어느 노선을 몇 번 물었는지도 여기서 센다. */
    private final Map<String, TransferPredictionResult> predictions = new LinkedHashMap<>();
    private final List<String> askedRoutes = new ArrayList<>();

    /** 호출마다 넘긴 시간 예산. 마감 직전 호출이 예산을 넘기지 않는지 본다. */
    private final List<Long> budgets = new ArrayList<>();

    /** 이 노선을 물으면 터진다. 일시적 장애를 흉내 낸다. */
    private final List<String> exploding = new ArrayList<>();

    /** 예측 한 번마다 시간이 흐른다. 시간 예산을 시험하려면 시계가 움직여야 한다. */
    private Duration tickPerPrediction = Duration.ZERO;
    private Instant currentTime = NOW;

    private RouteOptionCandidateProvider provider;

    @BeforeEach
    void setUp() {
        Clock steppingClock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return currentTime; }
        };
        provider = new RouteOptionCandidateProvider(transferRepository, legRepository,
                new StoredRouteOptionSource(routeOptionRepository), predictTransferUseCase,
                new TransferPredictionInterpreter(new JourneyEvaluatorProperties()),
                new RouteOptionEtaCalculator(), rideDurationPort, properties, metrics, steppingClock);

        // 기본: 어느 노선이든 OTP 시간표 소요가 있다고 본다. 값(600)은 기준선·후보에 똑같이 승차
        // leg 소요를 대체하므로 개선폭 뺄셈에서 상쇄된다 — 개선폭 단정은 종전대로 유지된다.
        // 공정성 게이트를 시험하는 케이스만 이 스텁을 바꾼다.
        when(rideDurationPort.rideDurationSeconds(anyString(), anyString(), anyString(), any()))
                .thenReturn(RideDurationResult.ok(600));

        givenTransfer(1, 2);
        givenLegs(defaultLegs());
        when(predictTransferUseCase.predict(anyString(), anyString(), anyInt(), any(), any(),
                anyLong())).thenAnswer(invocation -> {
                    String routeId = invocation.getArgument(1);
                    askedRoutes.add(routeId);
                    budgets.add(invocation.getArgument(5));
                    currentTime = currentTime.plus(tickPerPrediction);
                    if (exploding.contains(routeId)) {
                        throw new IllegalStateException("실시간 API 500");
                    }
                    return predictions.getOrDefault(routeId, noResult(routeId));
                });
    }

    // ── 후보가 나오는 경우 ──────────────────────────────────────────────────

    @Test
    @DisplayName("더 빨리 목적지에 닿는 노선을 후보로 올린다")
    void fasterRouteBecomesCandidate() {
        givenOptions(selected("BL-370", "UP"), alternative(101L, 1, "BL-721", "UP"));
        predicts("BL-370", READY_AT.plusSeconds(600));
        predicts("BL-721", READY_AT.plusSeconds(60));

        List<RerouteCandidate> candidates = provider.findCandidates(context());

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.display().etaGainSec())
                    .as("승차 시각 차이가 그대로 목적지 도착 차이가 된다")
                    .isEqualTo(540);
            assertThat(candidate.apply()).isEqualTo(
                    new RerouteCandidateApply.RouteOptionApply(2, 1, "BL-721", "UP"));
            assertThat(candidate.candidateId())
                    .as("ID 는 저장 쪽이 UUID 로 채운다")
                    .isNull();
        });
    }

    @Test
    @DisplayName("경로 검색을 부르지 않는다 — 이 칸의 존재 이유다")
    void neverCallsPathSearch() {
        givenOptions(selected("BL-370", "UP"), alternative(101L, 1, "BL-721", "UP"));
        predicts("BL-370", READY_AT.plusSeconds(600));
        predicts("BL-721", READY_AT.plusSeconds(60));

        provider.findCandidates(context());

        // 승·하차 정류장이 그대로라 물어볼 것은 "이 노선이 언제 오나"뿐이다.
        assertThat(askedRoutes).containsExactly("BL-370", "BL-721");
    }

    @Test
    @DisplayName("개선폭 큰 순으로 준다 — 절단은 저장 쪽이 한다")
    void sortedByGainDescending() {
        properties.getRouteOption().setMaxEvaluations(3);
        givenOptions(selected("BL-370", "UP"),
                alternative(101L, 1, "BL-721", "UP"),
                alternative(102L, 2, "BL-802", "UP"));
        predicts("BL-370", READY_AT.plusSeconds(900));
        predicts("BL-721", READY_AT.plusSeconds(300));
        predicts("BL-802", READY_AT.plusSeconds(60));

        List<RerouteCandidate> candidates = provider.findCandidates(context());

        assertThat(candidates).extracting(c -> c.apply())
                .containsExactly(
                        new RerouteCandidateApply.RouteOptionApply(2, 2, "BL-802", "UP"),
                        new RerouteCandidateApply.RouteOptionApply(2, 1, "BL-721", "UP"));
    }

    @Test
    @DisplayName("이후 환승이 남아 있으면 신뢰도를 낮춘다 — 절대 시각이 낙관적이다")
    void furtherTransferDowngradesConfidence() {
        givenLegs(List.of(
                leg(0, JourneyLegType.WALK, 300, null, null),
                leg(1, JourneyLegType.RIDE_BUS, 600, null, null),
                leg(2, JourneyLegType.WAIT_BUS, null, "ST-A", "정류장A"),
                leg(3, JourneyLegType.RIDE_BUS, 900, null, null),
                leg(4, JourneyLegType.WAIT_BUS, null, null, null),
                leg(5, JourneyLegType.RIDE_BUS, 600, null, null)));
        givenOptions(selected("BL-370", "UP"), alternative(101L, 1, "BL-721", "UP"));
        predicts("BL-370", READY_AT.plusSeconds(600));
        predicts("BL-721", READY_AT.plusSeconds(60));

        List<RerouteCandidate> candidates = provider.findCandidates(context());

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.display().confidence())
                    .isEqualTo(CurrentLegArrival.Quality.APPROXIMATE.name());
            assertThat(candidate.display().transferCount()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("정류장 이름이 비어 있어도 후보를 만든다")
    void missingStopNamesDoNotDropCandidate() {
        givenLegs(List.of(
                leg(0, JourneyLegType.WAIT_BUS, null, "ST-A", null),
                leg(1, JourneyLegType.RIDE_BUS, 900, null, null)));
        givenTransfer(1, 0);
        givenOptions(selected("BL-370", "UP"), alternative(101L, 1, "BL-721", "UP"));
        predicts("BL-370", READY_AT.plusSeconds(600));
        predicts("BL-721", READY_AT.plusSeconds(60));

        assertThat(provider.findCandidates(context()))
                .as("이름이 없다고 '더 빠른 버스'라는 사실이 사라지지 않는다")
                .singleElement()
                .satisfies(candidate -> assertThat(candidate.display().legSummaries())
                        .doesNotContainNull());
    }

    // ── 후보가 나오지 않는 경우 ─────────────────────────────────────────────

    @Test
    @DisplayName("개선폭이 임계값 미만이면 빈손이다 — 예외가 아니다")
    void gainBelowThresholdIsDropped() {
        givenOptions(selected("BL-370", "UP"), alternative(101L, 1, "BL-721", "UP"));
        predicts("BL-370", READY_AT.plusSeconds(300));
        predicts("BL-721", READY_AT.plusSeconds(120));

        assertThat(provider.findCandidates(context()))
                .as("예측 오차보다 작은 차이는 사용자가 뛸 이유가 되지 못한다")
                .isEmpty();
        assertThat(filtered("gain-below-threshold")).isEqualTo(1);
    }

    @Test
    @DisplayName("기준선이 못 타는 상태면 빈손이다 — 잴 기준이 없다")
    void unboardableBaselineYieldsNothing() {
        givenOptions(selected("BL-370", "UP"), alternative(101L, 1, "BL-721", "UP"));
        predictsPassed("BL-370", READY_AT.minusSeconds(60));
        predicts("BL-721", READY_AT.plusSeconds(60));

        assertThat(provider.findCandidates(context())).isEmpty();
        assertThat(askedRoutes)
                .as("기준선이 무너지면 대안을 물을 이유가 없다 — 외부 호출을 아낀다")
                .containsExactly("BL-370");
        assertThat(filtered("baseline-unboardable")).isEqualTo(1);
    }

    @Test
    @DisplayName("탈 수 없는 예측은 후보로 올리지 않는다 — 지나간 차를 답으로 준다")
    void unboardableCandidateIsDropped() {
        givenOptions(selected("BL-370", "UP"), alternative(101L, 1, "BL-721", "UP"));
        predicts("BL-370", READY_AT.plusSeconds(900));
        predictsPassed("BL-721", READY_AT.minusSeconds(120));

        assertThat(provider.findCandidates(context())).isEmpty();
        assertThat(filtered("unboardable")).isEqualTo(1);
    }

    @Test
    @DisplayName("예측이 없으면 그 후보만 버린다")
    void missingPredictionDropsOnlyThatCandidate() {
        givenOptions(selected("BL-370", "UP"),
                alternative(101L, 1, "BL-721", "UP"),
                alternative(102L, 2, "BL-802", "UP"));
        predicts("BL-370", READY_AT.plusSeconds(900));
        predicts("BL-802", READY_AT.plusSeconds(60));

        assertThat(provider.findCandidates(context())).singleElement()
                .satisfies(c -> assertThat(c.apply()).isEqualTo(
                        new RerouteCandidateApply.RouteOptionApply(2, 2, "BL-802", "UP")));
        assertThat(filtered("no-prediction")).isEqualTo(1);
    }

    @Test
    @DisplayName("지하철 환승은 빈 결과다 — 같은 승·하차 구간이라는 전제가 깨진다")
    void subwayTransferIsOutOfScope() {
        givenLegs(List.of(
                leg(0, JourneyLegType.WALK, 300, null, null),
                leg(1, JourneyLegType.RIDE_BUS, 600, null, null),
                leg(2, JourneyLegType.WAIT_SUBWAY, null, "ST-A", "정류장A"),
                leg(3, JourneyLegType.RIDE_SUBWAY, 900, null, null)));
        givenOptions(selected("SL-2", "UP"), alternative(101L, 1, "SL-3", "UP"));

        assertThat(provider.findCandidates(context())).isEmpty();
        assertThat(askedRoutes).isEmpty();
    }

    @Test
    @DisplayName("소요가 깨진 여정은 후보를 만들지 않는다")
    void brokenDurationsDropCandidate() {
        givenLegs(List.of(
                leg(0, JourneyLegType.WAIT_BUS, null, "ST-A", "정류장A"),
                leg(1, JourneyLegType.RIDE_BUS, null, null, null)));
        givenTransfer(1, 0);
        givenOptions(selected("BL-370", "UP"), alternative(101L, 1, "BL-721", "UP"));
        predicts("BL-370", READY_AT.plusSeconds(60));

        assertThat(provider.findCandidates(context()))
                .as("0으로 치면 도착 시각이 실제보다 빨라져 없는 개선폭이 생긴다")
                .isEmpty();
    }

    @Test
    @DisplayName("예측이 터져도 요청을 죽이지 않는다")
    void predictionFailureIsSwallowed() {
        givenOptions(selected("BL-370", "UP"), alternative(101L, 1, "BL-721", "UP"));
        when(predictTransferUseCase.predict(anyString(), anyString(), anyInt(), any(), any(),
                anyLong())).thenThrow(new IllegalStateException("실시간 API 500"));

        assertThat(provider.findCandidates(context()))
                .as("재탐색 실패가 여정을 흔들면 안 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("한 후보가 터져도 앞서 모은 후보는 살아남는다")
    void oneFailingCandidateDoesNotDiscardTheRest() {
        properties.getRouteOption().setMaxEvaluations(3);
        givenOptions(selected("BL-370", "UP"),
                alternative(101L, 1, "BL-721", "UP"),
                alternative(102L, 2, "BL-802", "UP"));
        predicts("BL-370", READY_AT.plusSeconds(900));
        predicts("BL-721", READY_AT.plusSeconds(60));
        explodes("BL-802");

        assertThat(provider.findCandidates(context()))
                .as("세 번째 후보의 일시적 장애가 앞의 좋은 후보까지 날리면 안 된다")
                .singleElement()
                .satisfies(candidate -> assertThat(candidate.apply()).isEqualTo(
                        new RerouteCandidateApply.RouteOptionApply(2, 1, "BL-721", "UP")));
    }

    // ── 공정성 게이트(노선별 시간표 소요 비교 가능성) ──────────────────────────

    @Test
    @DisplayName("기준선의 노선 소요를 못 얻으면 어떤 후보도 실제 제안이 안 된다 — 불공정 비교")
    void baselineWithoutRideDurationShadowsAllCandidates() {
        when(rideDurationPort.rideDurationSeconds(eq("BL-370"), anyString(), anyString(), any()))
                .thenReturn(RideDurationResult.notFound());  // 기준선만 폴백
        givenOptions(selected("BL-370", "UP"), alternative(101L, 1, "BL-721", "UP"));
        predicts("BL-370", READY_AT.plusSeconds(900));
        predicts("BL-721", READY_AT.plusSeconds(60));

        assertThat(provider.findCandidates(context()))
                .as("기준선 승차 소요가 원래 값이면 후보(OTP)와 데이터 출처가 달라 비교가 불공정하다")
                .isEmpty();
        assertThat(filtered("ride-duration-incomparable")).isEqualTo(1);
    }

    @Test
    @DisplayName("소요를 얻은 후보만 제안하고, 못 얻은 후보는 shadow 로 뺀다")
    void candidateWithoutRideDurationIsShadowedWhileOthersPromote() {
        properties.getRouteOption().setMaxEvaluations(3);
        when(rideDurationPort.rideDurationSeconds(eq("BL-721"), anyString(), anyString(), any()))
                .thenReturn(RideDurationResult.notFound());  // 한 후보만 폴백
        givenOptions(selected("BL-370", "UP"),
                alternative(101L, 1, "BL-721", "UP"),
                alternative(102L, 2, "BL-802", "UP"));
        predicts("BL-370", READY_AT.plusSeconds(900));
        predicts("BL-721", READY_AT.plusSeconds(30));   // 개선폭은 크지만 소요 폴백이라 shadow
        predicts("BL-802", READY_AT.plusSeconds(60));

        assertThat(provider.findCandidates(context()))
                .singleElement()
                .satisfies(c -> assertThat(c.apply()).isEqualTo(
                        new RerouteCandidateApply.RouteOptionApply(2, 2, "BL-802", "UP")));
        assertThat(filtered("ride-duration-incomparable")).isEqualTo(1);
    }

    // ── 평가 대상 추리기 ────────────────────────────────────────────────────

    @Test
    @DisplayName("지금 선택된 노선은 대안이 아니다 — 기준선으로만 쓴다")
    void selectedRouteIsNotItsOwnAlternative() {
        givenOptions(selected("BL-370", "UP"), alternative(101L, 1, "BL-721", "UP"));
        predicts("BL-370", READY_AT.plusSeconds(600));
        predicts("BL-721", READY_AT.plusSeconds(60));

        provider.findCandidates(context());

        assertThat(askedRoutes).containsExactly("BL-370", "BL-721");
    }

    @Test
    @DisplayName("같은 노선·같은 방향이 두 번 들어오면 한 번만 평가한다")
    void duplicateRoutesAreEvaluatedOnce() {
        givenOptions(selected("BL-370", "UP"),
                alternative(101L, 1, "BL-721", "UP"),
                alternative(102L, 2, "BL-721", "UP"));
        predicts("BL-370", READY_AT.plusSeconds(900));
        predicts("BL-721", READY_AT.plusSeconds(60));

        provider.findCandidates(context());

        assertThat(askedRoutes)
                .as("같은 예측을 두 번 부르면 평가 상한만 깎인다")
                .containsExactly("BL-370", "BL-721");
    }

    @Test
    @DisplayName("노선을 해석하지 못한 후보는 평가하지 않는다")
    void unresolvedRoutesAreSkipped() {
        JourneyLegRouteOption broken = alternative(101L, 1, "", "UP");
        JourneyLegRouteOption noSeq = alternative(102L, 2, "BL-802", "UP");
        ReflectionTestUtils.setField(noSeq, "boardingSeq", null);
        givenOptions(selected("BL-370", "UP"), broken, noSeq);
        predicts("BL-370", READY_AT.plusSeconds(900));

        provider.findCandidates(context());

        assertThat(askedRoutes).containsExactly("BL-370");
    }

    @Test
    @DisplayName("평가 상한을 넘는 대안은 보지 않는다")
    void maxEvaluationsCapsTheFanOut() {
        properties.getRouteOption().setMaxEvaluations(1);
        givenOptions(selected("BL-370", "UP"),
                alternative(101L, 1, "BL-721", "UP"),
                alternative(102L, 2, "BL-802", "UP"));
        predicts("BL-370", READY_AT.plusSeconds(900));
        predicts("BL-721", READY_AT.plusSeconds(60));
        predicts("BL-802", READY_AT.plusSeconds(30));

        provider.findCandidates(context());

        assertThat(askedRoutes).containsExactly("BL-370", "BL-721");
    }

    @Test
    @DisplayName("시간 예산을 넘기면 남은 후보를 건너뛰고 이미 평가한 것으로 제안한다")
    void budgetStopsFurtherEvaluations() {
        properties.getRouteOption().setEvaluationBudgetMs(1_000);
        properties.getRouteOption().setMaxEvaluations(3);
        tickPerPrediction = Duration.ofMillis(800);
        givenOptions(selected("BL-370", "UP"),
                alternative(101L, 1, "BL-721", "UP"),
                alternative(102L, 2, "BL-802", "UP"),
                alternative(103L, 3, "BL-900", "UP"));
        predicts("BL-370", READY_AT.plusSeconds(900));
        predicts("BL-721", READY_AT.plusSeconds(60));
        predicts("BL-802", READY_AT.plusSeconds(30));
        predicts("BL-900", READY_AT.plusSeconds(10));

        List<RerouteCandidate> candidates = provider.findCandidates(context());

        assertThat(askedRoutes)
                .as("기준선 평가는 예산에서 빼지 않는다. 예산을 넘긴 뒤의 대안만 건너뛴다")
                .containsExactly("BL-370", "BL-721", "BL-802");
        assertThat(candidates)
                .as("부분 결과가 빈손보다 낫다")
                .hasSize(2);
        assertThat(filtered("budget-exhausted")).isEqualTo(1);
    }

    @Test
    @DisplayName("남은 예산이 짧으면 그만큼만 기다린다 — 마감 직전 호출이 예산을 넘기지 않게")
    void remainingBudgetCapsTheNextCall() {
        properties.getRouteOption().setEvaluationBudgetMs(1_000);
        properties.getRouteOption().setMaxEvaluations(3);
        tickPerPrediction = Duration.ofMillis(800);
        givenOptions(selected("BL-370", "UP"),
                alternative(101L, 1, "BL-721", "UP"),
                alternative(102L, 2, "BL-802", "UP"));
        predicts("BL-370", READY_AT.plusSeconds(900));
        predicts("BL-721", READY_AT.plusSeconds(60));
        predicts("BL-802", READY_AT.plusSeconds(30));

        provider.findCandidates(context());

        // 기준선은 예산 밖이라 제 몫(1500)을 받는다. 첫 대안도 예산이 넉넉해 1500.
        // 800ms 를 쓴 뒤 남은 예산은 200ms 뿐이라 두 번째 대안은 그만큼만 받는다.
        assertThat(budgets).containsExactly(1_500L, 1_000L, 200L);
    }

    @Test
    @DisplayName("예측을 몇 번 불렀는지 센다 — 요금 추정용")
    void countsEveryPredictionIncludingBaseline() {
        givenOptions(selected("BL-370", "UP"), alternative(101L, 1, "BL-721", "UP"));
        predicts("BL-370", READY_AT.plusSeconds(900));
        predicts("BL-721", READY_AT.plusSeconds(60));

        provider.findCandidates(context());

        assertThat(registry.get(JourneyRerouteMetrics.CANDIDATE_EVALUATED).counter().count())
                .isEqualTo(2);
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private RerouteContext context() {
        return new RerouteContext(JOURNEY_ID, 7L, 1, 1L, 0, NOW);
    }

    private void givenTransfer(int transferIndex, int toLegIndex) {
        TransferConnection transfer = TransferConnection.builder()
                .journeyId(JOURNEY_ID)
                .routeRevision(1)
                .transferIndex(0)
                .fromLegIndex(Math.max(0, toLegIndex - 1))
                .toLegIndex(toLegIndex)
                .status(TransferStatus.AT_RISK)
                .build();
        transfer.applyAssessment(TransferStatus.AT_RISK, NOW, READY_AT, NOW.plusSeconds(600), 60,
                "REALTIME", CurrentLegArrival.Quality.GOOD.name(), NOW, NOW);
        when(transferRepository.findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(
                anyLong(), anyInt(), anyInt())).thenReturn(Optional.of(transfer));
    }

    private void givenLegs(List<JourneyLeg> legs) {
        when(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                anyLong(), anyInt())).thenReturn(legs);
    }

    // ── 동적(OTP) 후보 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("저장 목록에 없는 동적(OTP) 후보는 스냅샷을 담은 DYNAMIC apply 로 만든다")
    void dynamicCandidateCarriesSnapshotApply() {
        // 기준선(저장 selected)은 느리고, 동적 후보는 빨라 제안이 나가야 한다.
        JourneyLegRouteOption dynamic = JourneyLegRouteOption.builder()
                .journeyLegId(3L)
                .optionIndex(OtpPatternRouteOptionSource.DYNAMIC_OPTION_INDEX)
                .laneIndex(0)
                .routeId("OTP-9")
                .sourceRouteId("1:BR_TAGO_SEB100100999")
                .routeName("9 번")
                .directionKey("OTP-9|-1")
                .boardingSeq(3)
                .build(); // id 는 null — 저장 전 임시 엔티티.
        // 저장 selected(기준선) + 동적 후보를 내는 출처를 직접 물린다.
        RerouteRouteOptionSource source = (leg, deadline) ->
                RerouteRouteOptionSource.SourceOutcome.ok(List.of(selected("BL-370", "UP"), dynamic));
        provider = new RouteOptionCandidateProvider(transferRepository, legRepository, source,
                predictTransferUseCase, new TransferPredictionInterpreter(new JourneyEvaluatorProperties()),
                new RouteOptionEtaCalculator(), rideDurationPort, properties, metrics, Clock.fixed(NOW, ZoneId.of("UTC")));
        predicts("BL-370", READY_AT.plusSeconds(600));
        predicts("OTP-9", READY_AT.plusSeconds(60));

        List<RerouteCandidate> candidates = provider.findCandidates(context());

        assertThat(candidates).hasSize(1);
        // routeOptionIndex=DYNAMIC(-1) + 스냅샷 전부. PR-3 수락 계약이 이걸로 새 행을 만든다.
        assertThat(candidates.get(0).apply()).isEqualTo(
                new RerouteCandidateApply.RouteOptionApply(2, RerouteCandidateApply.RouteOptionApply.DYNAMIC,
                        "OTP-9", "OTP-9|-1", "1:BR_TAGO_SEB100100999", "9 번", null, 3, null, null));
    }

    private void givenOptions(JourneyLegRouteOption... options) {
        when(routeOptionRepository.findByJourneyLegIdInAndDeletedAtIsNull(any()))
                .thenReturn(List.of(options));
    }

    /** 대기 단계는 legIndex 2 다. 그 앞은 걷기·타고 가기, 뒤는 타고 가기·걷기. */
    private List<JourneyLeg> defaultLegs() {
        return List.of(
                leg(0, JourneyLegType.WALK, 300, null, null),
                leg(1, JourneyLegType.RIDE_BUS, 600, null, null),
                leg(2, JourneyLegType.WAIT_BUS, null, "ST-A", "정류장A"),
                leg(3, JourneyLegType.RIDE_BUS, 900, null, "정류장F"),
                leg(4, JourneyLegType.WALK, 180, null, null));
    }

    private JourneyLeg leg(int legIndex, JourneyLegType type, Integer durationSec,
                           String startStopId, String name) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(JOURNEY_ID)
                .routeRevision(1)
                .legIndex(legIndex)
                .type(type)
                .state(JourneyLegState.PENDING)
                .startStopId(startStopId)
                // OTP 정류장 gtfsId 자리. rideSec 조회 가드(비어 있으면 폴백)를 통과하게 채운다.
                .sourceStartStopId("OTP-S" + legIndex)
                .sourceEndStopId("OTP-E" + legIndex)
                .startName(name)
                .endName(name)
                .plannedDurationSec(durationSec)
                .build();
        ReflectionTestUtils.setField(leg, "id", (long) legIndex + 1);
        if (type.isWaiting()) {
            leg.selectRouteOption(SELECTED_OPTION_ID, "BL-370");
        }
        return leg;
    }

    private JourneyLegRouteOption selected(String routeId, String directionKey) {
        return option(SELECTED_OPTION_ID, 0, routeId, directionKey);
    }

    private JourneyLegRouteOption alternative(long id, int optionIndex, String routeId,
                                              String directionKey) {
        return option(id, optionIndex, routeId, directionKey);
    }

    private JourneyLegRouteOption option(long id, int optionIndex, String routeId,
                                         String directionKey) {
        JourneyLegRouteOption option = JourneyLegRouteOption.builder()
                .journeyLegId(3L)
                .optionIndex(optionIndex)
                .laneIndex(optionIndex)
                .routeId(routeId)
                // OTP route gtfsId 자리(rideSec 조회 열쇠). 실제 저장 후보는 seoul id 라 미매칭이지만
                // 여기선 조회 가드 통과 + 공정성 게이트 검증을 위해 non-blank 로 채운다.
                .sourceRouteId(routeId)
                .routeName(routeId + " 번")
                .directionKey(directionKey)
                .boardingSeq(3)
                .alightingSeq(9)
                .build();
        ReflectionTestUtils.setField(option, "id", id);
        return option;
    }

    private void predicts(String routeId, Instant arrivalAt) {
        predictions.put(routeId, TransferPredictionResult.available(
                "ST-A", routeId, 3, TransferArrival.Source.REALTIME, NOW, READY_AT, arrivalAt,
                null, null));
    }

    /**
     * 탈 수 있는 차가 없어 <b>이미 지나간 차</b>를 돌려준 경우.
     *
     * <p>같은 생성자를 쓴다 — 준비 시각보다 이른 도착이면 {@code boardable} 이 스스로 false 가
     * 된다. 그 계산을 시험이 흉내 내면 실제 규칙과 갈릴 수 있다.
     */
    private void predictsPassed(String routeId, Instant arrivalAt) {
        predicts(routeId, arrivalAt);
    }

    private void explodes(String routeId) {
        exploding.add(routeId);
    }

    private TransferPredictionResult noResult(String routeId) {
        return TransferPredictionResult.noResult("ST-A", routeId, 3,
                TransferArrival.Source.REALTIME, TransferArrival.Status.NO_VEHICLE, NOW, READY_AT);
    }

    private double filtered(String reason) {
        try {
            return registry.get(JourneyRerouteMetrics.CANDIDATE_FILTERED)
                    .tag("reason", reason).counter().count();
        } catch (MeterNotFoundException e) {
            return 0;
        }
    }
}
