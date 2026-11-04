package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.BoardingEstimate;
import watoo.grd.nextroute.application.journey.dto.BoardingOutcome;
import watoo.grd.nextroute.application.journey.dto.BoardingQuery;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Quality;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Source;
import watoo.grd.nextroute.application.journey.dto.TransferTargetArrival;
import watoo.grd.nextroute.application.journey.observability.JourneyEvaluationMetrics;
import watoo.grd.nextroute.application.journey.port.out.TransferTargetArrivalPredictor;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 갈아탈 버스가 사용자 도착 시각에 언제 오는지.
 *
 * <p>실시간 우선, 못 잡으면 모델. 그 규칙은 {@code SingleTransferPredictor} 가 이미 가지고 있고
 * <b>기준 시각을 인자로 받는다.</b> 그래서 여기서 하는 일은 앵커를 정확히 넘겨 주는 것과, <b>어떤
 * 근거까지 쓸지 고르는 것</b>뿐이다. 규칙을 다시 만들면 검색 경로와 여정 경로의 판정이 갈린다.
 *
 * <h2>근거를 앵커 거리로 고른다</h2>
 *
 * <ul>
 *   <li><b>20분 안에 탄다</b> — 실시간을 먼저 본다. 탈 수 있는 차량을 못 잡으면 모델로 내려간다</li>
 *   <li><b>그보다 멀다</b> — 실시간을 건너뛰고 모델만 쓴다. 도착 API 는 지금 접근 중인 다음 한두
 *       대만 알려 주므로 그 시각에 탈 차량은 애초에 목록에 없다</li>
 * </ul>
 *
 * <p>모델도 답을 못 주면 모른다고 한다. 운행 중인 차량이 없으면 추정할 근거가 없고, 그 판단은
 * 데이터를 보는 모델 쪽이 하는 것이 맞다 — 여기서 거리로 미리 자르지 않는다.
 *
 * <p>근거 고르기와 결과 해석은 {@link TransferPredictionInterpreter} 가 소유한다. 재탐색의 승차
 * 추정({@link BusBoardingTimeEstimator})이 같은 규칙을 써야 하기 때문이다 — 두 곳이 각자
 * 해석하면 같은 버스를 두고 평가와 재탐색이 서로 다른 답을 낸다.
 *
 * <p>{@link WaitBusArrivalPredictor} 와 달리 단계가 시작됐는지 보지 않는다. 아직 버스를 타고 있는
 * 사용자에게 "다음 정류장에서 갈아탈 차가 언제 오나"를 알려 주는 것이 이 클래스의 존재 이유다.
 *
 * <p>추정 자체는 {@link BusBoardingTimeEstimator} 가 한다. 여기 남는 것은 <b>저장된 단계에서
 * 값을 꺼내는 일</b>(선택 노선 DB 읽기)과 결과를 여정 평가의 모양으로 옮기는 일뿐이다 — 재탐색이
 * 검색해 온 후보 경로는 DB 에 없어서 같은 추정을 단계 없이도 물어볼 수 있어야 한다.
 *
 * <h2>경기·TAGO 노선</h2>
 *
 * <p>서울 노선 id·순번이 없어 그 길로 못 간다. 원천 id 로 지역 실시간({@link RegionalArrivalLookup})에 묻고, 준비 시각 뒤 첫
 * 버스를 쓴다. 모델이 없어 실시간 시야 밖이거나 탈 차가 응답에 없으면 모른다고 한다. 조회 몫은 재탐색 지역 버킷을 함께 쓴다.
 */
@Component
@RequiredArgsConstructor
public class BusTransferTargetArrivalPredictor implements TransferTargetArrivalPredictor {

    /** 관측용 mode tag. 서울 버스와 못 구하는 이유가 달라 나눠 센다. */
    private static final String REGIONAL_MODE = "regional_bus";

    private final BusBoardingTimeEstimator boardingTimeEstimator;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final TransferPredictionInterpreter interpreter;
    private final RegionalArrivalLookup regionalArrivalLookup;
    private final JourneyEvaluationMetrics metrics;

    @Override
    public boolean supports(JourneyLegType type) {
        return type == JourneyLegType.WAIT_BUS;
    }

    @Override
    public TransferTargetArrival predictBoarding(JourneyLeg waitLeg, Instant boardingAnchorAt, Instant now) {
        if (boardingAnchorAt == null) {
            // 앵커가 없으면 물어볼 기준이 없다. 지금 시각으로 대신하지 않는다 — 사용자가 도착하기
            // 전에 지나갈 차량을 답으로 내놓게 된다.
            return TransferTargetArrival.unknown();
        }
        Optional<JourneyLegRouteOption> option = selectedOption(waitLeg);
        RegionalBusProvider regional = option
                .map(o -> RegionalArrivalLookup.providerOf(o.getRouteId(), o.getSourceRouteId(),
                        waitLeg.getSourceStartStopId()))
                .orElse(null);
        if (regional != null) {
            return predictRegional(waitLeg, option.get(), regional, boardingAnchorAt, now);
        }
        BoardingQuery query = new BoardingQuery(
                JourneyLegType.WAIT_BUS,
                waitLeg.getStartStopId(),
                waitLeg.getStartName(),
                option.map(JourneyLegRouteOption::getRouteId).orElse(null),
                option.map(JourneyLegRouteOption::getBoardingSeq).orElse(null),
                null);

        // 평가기 몫의 시간 예산을 명시한다. 검색용 기본값을 그대로 쓰면 환승 두 개짜리 여정이
        // 배치 상한을 넘고, 밀리면 선점이 만료돼 계산 결과를 통째로 버린다.
        BoardingOutcome outcome = boardingTimeEstimator.estimate(
                query, boardingAnchorAt, now, interpreter.budgetMs());

        if (!outcome.isPresent()) {
            // 막혀서 못 물어본 것과 물어봤지만 근거가 없는 것을 가른다. 막힌 상황에서 30초마다
            // 다시 부르면 막힌 문을 계속 두드린다.
            return outcome.blocked() ? TransferTargetArrival.blocked() : TransferTargetArrival.unknown();
        }
        // 못 타는 차량이어도 그대로 통과시킨다. 그 음수 여유가 "환승 놓침" 판정의 근거다.
        BoardingEstimate estimate = outcome.estimate();
        return new TransferTargetArrival(new CurrentLegArrival(
                estimate.boardingAt(),
                (int) Math.max(0, Duration.between(now, estimate.boardingAt()).getSeconds()),
                null,
                null,
                estimate.source(),
                estimate.quality(),
                estimate.observedAt()),
                estimate.modelVersion(),
                false);
    }

    private TransferTargetArrival predictRegional(JourneyLeg waitLeg, JourneyLegRouteOption option,
                                                  RegionalBusProvider provider, Instant boardingAnchorAt, Instant now) {
        if (interpreter.modeFor(boardingAnchorAt, now) == PredictTransferUseCase.Mode.MODEL_ONLY) {
            // 그 시각에 탈 차는 응답에 없고 대신할 모델도 없다. 답이 없을 호출로 몫을 쓰지 않는다.
            metrics.recordBoardingMiss(REGIONAL_MODE, "beyond-window");
            return TransferTargetArrival.unknown();
        }
        RegionalArrivalLookup.Lookup lookup = regionalArrivalLookup.boardableAt(
                provider, option.getSourceRouteId(), waitLeg.getSourceStartStopId(), boardingAnchorAt);
        if (lookup.failed()) {
            metrics.recordBoardingMiss(REGIONAL_MODE, lookup.failureReason());
            // 몫 소진만 막힘이다. 조회 실패에는 순번이 안 정해지는 정류장처럼 계속 그런 경우가 섞여 있어, 평가 간격을 늦추면 안 된다.
            return lookup.quotaExhausted() ? TransferTargetArrival.blocked() : TransferTargetArrival.unknown();
        }
        return TransferTargetArrival.of(new CurrentLegArrival(
                lookup.at(),
                (int) Math.max(0, Duration.between(now, lookup.at()).getSeconds()),
                null,
                null,
                Source.REALTIME_ARRIVAL,
                // 경기는 분 단위 예측이라 근사다.
                provider == RegionalBusProvider.TAGO ? Quality.GOOD : Quality.APPROXIMATE,
                now));
    }

    private Optional<JourneyLegRouteOption> selectedOption(JourneyLeg leg) {
        if (leg.getSelectedRouteOptionId() == null) {
            return Optional.empty();
        }
        return routeOptionRepository.findById(leg.getSelectedRouteOptionId());
    }
}
