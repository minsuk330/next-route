package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Quality;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Source;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;

import java.time.Duration;
import java.time.Instant;

/**
 * 환승 예측을 <b>어떻게 묻고 어떻게 읽을지</b>. 규칙 자체는 여기 한 곳에 있다.
 *
 * <p>같은 예측을 두 곳이 쓴다 — 주기 평가({@link BusTransferTargetArrivalPredictor})와 재탐색
 * 후보 평가({@link BusBoardingTimeEstimator}). <b>두 곳이 각자 규칙을 가지면 같은 버스를 두고
 * 서로 다른 답을 낸다</b>: 평가는 "탈 수 있다"고 하는데 재탐색은 "이 노선이 더 낫다"고 하는 화면이
 * 나온다. 개선폭은 두 값의 뺄셈이므로 계산 방식이 갈리면 개선폭이 계산 방식의 차이가 된다.
 *
 * <p>여기 있는 것은 <b>해석</b>뿐이다. 실시간→모델 순서 같은 예측 규칙은 여전히
 * {@code SingleTransferPredictor} 소유다.
 */
@Component
@RequiredArgsConstructor
public class TransferPredictionInterpreter {

    private final JourneyEvaluatorProperties properties;

    /**
     * 실시간까지 볼지, 모델만 볼지. <b>앵커까지의 거리</b>가 정한다.
     *
     * <p>앵커가 지났거나 가까우면 실시간이 답을 줄 수 있다. 멀면 도착 API 는 지금 접근 중인 다음
     * 한두 대만 알려 주므로 그 시각에 탈 차량이 애초에 목록에 없다 — 답이 없을 호출을 하는 셈이다.
     */
    public PredictTransferUseCase.Mode modeFor(Instant boardingAnchorAt, Instant now) {
        boolean withinRealtimeWindow = Duration.between(now, boardingAnchorAt)
                .compareTo(properties.busRealtimeWindow()) <= 0;
        return withinRealtimeWindow
                ? PredictTransferUseCase.Mode.REALTIME_THEN_MODEL
                : PredictTransferUseCase.Mode.MODEL_ONLY;
    }

    /**
     * 한 번 물어보는 데 쓸 시간(ms).
     *
     * <p>검색용 기본값(2500ms)을 그대로 쓰면 환승 두 개짜리 여정이 배치 상한을 넘고, 밀리면
     * 선점이 만료돼 계산 결과를 통째로 버린다. 재탐색도 같은 값을 쓴다 — 사용자가 화면에서
     * 기다리는 요청이라 한 번의 조회가 길어지면 그만큼 그대로 체감된다.
     */
    public long budgetMs() {
        return properties.getPredictDeadlineMs();
    }

    /** 실시간으로 잡았는지 모델이 추정했는지. 운영이 그 비율을 보고 모델 품질을 판단한다. */
    public Source sourceOf(TransferPredictionResult prediction) {
        return prediction.source() == TransferArrival.Source.REALTIME
                ? Source.REALTIME_ARRIVAL : Source.MODEL_ARRIVAL;
    }

    /**
     * 실시간으로 잡은 차량이면 믿을 만하고, 모델 추정이면 오차가 크다.
     *
     * <p>{@code ARRIVAL_TIME_APPROXIMATE} 는 실시간이어도 도착시간이 근사라는 표시라서 함께 낮춘다.
     */
    public Quality qualityOf(TransferPredictionResult prediction) {
        boolean realtime = prediction.source() == TransferArrival.Source.REALTIME;
        boolean exact = prediction.status() == TransferArrival.Status.AVAILABLE;
        return realtime && exact ? Quality.GOOD : Quality.APPROXIMATE;
    }

    /**
     * 이 예측으로 <b>실제로 탈 수 있는가</b>.
     *
     * <p>시각이 있다고 탈 수 있는 것이 아니다. 탈 수 있는 차가 없으면 예측이
     * <b>이미 지나간 차</b>를 돌려주는 경로가 있고({@code TransferPredictionResult.boardable}),
     * 그 시각을 그대로 쓰면 "지나간 버스를 타면 5분 빠릅니다"가 제안으로 나간다.
     */
    public boolean isBoardable(TransferPredictionResult prediction) {
        return prediction.predictedArrivalAt() != null
                && Boolean.TRUE.equals(prediction.boardable());
    }

    /** 물어보는 것 자체가 막힌 상태인지. 막힌 문을 30초마다 다시 두드리지 않으려고 가른다. */
    public boolean isBlocked(TransferPredictionResult prediction) {
        return prediction.status() == TransferArrival.Status.BLOCKED
                || prediction.status() == TransferArrival.Status.LIMITED;
    }
}
