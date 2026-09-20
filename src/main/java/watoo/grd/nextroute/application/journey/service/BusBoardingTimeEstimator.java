package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.BoardingEstimate;
import watoo.grd.nextroute.application.journey.dto.BoardingOutcome;
import watoo.grd.nextroute.application.journey.dto.BoardingQuery;
import watoo.grd.nextroute.application.journey.observability.JourneyEvaluationMetrics;
import watoo.grd.nextroute.application.journey.port.out.BoardingTimeEstimator;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Instant;

/**
 * 버스 승차 시각 추정. 실시간 우선, 못 잡으면 모델 — 그 규칙은
 * {@code SingleTransferPredictor} 가 이미 가지고 있고 기준 시각을 인자로 받는다. 여기서 하는 일은
 * 앵커를 정확히 넘기는 것과 <b>어떤 근거까지 쓸지 고르는 것</b>뿐이고, 그 고르기와 해석은
 * {@link TransferPredictionInterpreter} 가 소유한다.
 *
 * <p><b>못 타는 차(boardable=false)를 걸러내지 않는다.</b> 그 판단은 소비자 몫이다 —
 * 여정 평가는 그 음수 여유로 "환승 놓침"을 판정하므로 걸러내면 감지가 죽고, 재탐색 후보 쪽만
 * 버린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusBoardingTimeEstimator implements BoardingTimeEstimator {

    /** 관측용 mode tag. 버스와 지하철은 못 구하는 이유가 달라서 나눠 센다. */
    private static final String MODE = "bus";

    private final PredictTransferUseCase predictTransferUseCase;
    private final TransferPredictionInterpreter interpreter;
    private final JourneyEvaluationMetrics metrics;

    @Override
    public boolean supports(JourneyLegType waitType) {
        return waitType == JourneyLegType.WAIT_BUS;
    }

    @Override
    public BoardingOutcome estimate(BoardingQuery query, Instant anchorAt, Instant now, long budgetMs) {
        if (anchorAt == null || !hasScope(query)) {
            // 미해석 정류장·노선으로 유료 조회를 내보내지 않는다.
            log.debug("버스 승차 추정 불가 — stopId={}, routeId={}, 노선·정류장 미해석",
                    query.stopId(), query.routeId());
            metrics.recordBoardingMiss(MODE, anchorAt == null ? "no_anchor" : "no_scope");
            return BoardingOutcome.none();
        }

        TransferPredictionResult prediction = predictTransferUseCase.predict(
                query.stopId(),
                query.routeId(),
                query.boardingSeq(),
                anchorAt,
                interpreter.modeFor(anchorAt, now),
                budgetMs);

        if (prediction.predictedArrivalAt() == null) {
            // 예측기가 준 사유를 그대로 남긴다. 이 값이 없으면 "왜 안 나왔나"를 사후에 알 방법이
            // 없다 — source=none 만으로는 막힌 것과 탈 차가 없던 것을 못 가른다.
            metrics.recordBoardingMiss(MODE, reasonOf(prediction));
            log.info("버스 승차 추정 없음 — stopId={}, routeId={}, seq={}, status={}, source={}",
                    query.stopId(), query.routeId(), query.boardingSeq(),
                    prediction.status(), prediction.source());
            // 막혀서 못 물어본 것과 물어봤지만 근거가 없는 것을 가른다.
            return interpreter.isBlocked(prediction) ? BoardingOutcome.ofBlocked() : BoardingOutcome.none();
        }
        return BoardingOutcome.of(new BoardingEstimate(
                prediction.predictedArrivalAt(),
                Boolean.TRUE.equals(prediction.boardable()),
                interpreter.sourceOf(prediction),
                interpreter.qualityOf(prediction),
                prediction.calculatedAt(),
                prediction.modelVersion()));
    }

    /** tag 값은 고정 집합이어야 한다 — 예측기 status 는 enum 이라 그대로 쓴다. */
    private static String reasonOf(TransferPredictionResult prediction) {
        return prediction.status() == null ? "unknown" : prediction.status().name().toLowerCase();
    }

    private boolean hasScope(BoardingQuery query) {
        return query.stopId() != null
                && query.routeId() != null && !query.routeId().isBlank()
                && query.boardingSeq() != null;
    }
}
