package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Quality;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Source;
import watoo.grd.nextroute.application.journey.dto.TransferTargetArrival;
import watoo.grd.nextroute.application.journey.port.out.TransferTargetArrivalPredictor;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
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
 * <p>{@link WaitBusArrivalPredictor} 와 달리 단계가 시작됐는지 보지 않는다. 아직 버스를 타고 있는
 * 사용자에게 "다음 정류장에서 갈아탈 차가 언제 오나"를 알려 주는 것이 이 클래스의 존재 이유다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusTransferTargetArrivalPredictor implements TransferTargetArrivalPredictor {

    private final PredictTransferUseCase predictTransferUseCase;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final JourneyEvaluatorProperties properties;

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
        if (option.isEmpty() || !hasBusScope(option.get()) || waitLeg.getStartStopId() == null) {
            log.debug("버스 환승 예측 불가 — legIndex={}, 노선·정류장 미해석", waitLeg.getLegIndex());
            return TransferTargetArrival.unknown();
        }

        PredictTransferUseCase.Mode mode = modeFor(boardingAnchorAt, now);
        // 평가기 몫의 시간 예산을 명시한다. 검색용 기본값(2500ms)을 그대로 쓰면 환승 두 개짜리
        // 여정이 배치 상한을 넘고, 밀리면 선점이 만료돼 계산 결과를 통째로 버린다.
        TransferPredictionResult prediction = predictTransferUseCase.predict(
                waitLeg.getStartStopId(),
                option.get().getRouteId(),
                option.get().getBoardingSeq(),
                boardingAnchorAt,
                mode,
                properties.getPredictDeadlineMs());

        if (prediction.predictedArrivalAt() == null) {
            // 막혀서 못 물어본 것과 물어봤지만 근거가 없는 것을 가른다. 막힌 상황에서 30초마다
            // 다시 부르면 막힌 문을 계속 두드린다.
            return prediction.status() == TransferArrival.Status.BLOCKED
                    || prediction.status() == TransferArrival.Status.LIMITED
                    ? TransferTargetArrival.blocked()
                    : TransferTargetArrival.unknown();
        }
        return new TransferTargetArrival(new CurrentLegArrival(
                prediction.predictedArrivalAt(),
                (int) Math.max(0, Duration.between(now, prediction.predictedArrivalAt()).getSeconds()),
                null,
                null,
                sourceOf(prediction),
                qualityOf(prediction),
                prediction.calculatedAt()),
                prediction.modelVersion(),
                false);
    }

    /**
     * 실시간까지 볼지, 모델만 볼지.
     *
     * <p>앵커가 지났거나 가까우면 실시간이 답을 줄 수 있다. 멀면 실시간 목록에 그 차량이 없으므로
     * 부르지 않는다.
     */
    private PredictTransferUseCase.Mode modeFor(Instant boardingAnchorAt, Instant now) {
        boolean withinRealtimeWindow = Duration.between(now, boardingAnchorAt)
                .compareTo(properties.busRealtimeWindow()) <= 0;
        return withinRealtimeWindow
                ? PredictTransferUseCase.Mode.REALTIME_THEN_MODEL
                : PredictTransferUseCase.Mode.MODEL_ONLY;
    }

    /** 실시간으로 잡았는지 모델이 추정했는지. 운영이 그 비율을 보고 모델 품질을 판단한다. */
    private Source sourceOf(TransferPredictionResult prediction) {
        return prediction.source() == TransferArrival.Source.REALTIME
                ? Source.REALTIME_ARRIVAL : Source.MODEL_ARRIVAL;
    }

    /**
     * 실시간으로 잡은 차량이면 믿을 만하고, 모델 추정이면 오차가 크다.
     *
     * <p>{@code ARRIVAL_TIME_APPROXIMATE} 는 실시간이어도 도착시간이 근사라는 표시라서 함께 낮춘다.
     */
    private Quality qualityOf(TransferPredictionResult prediction) {
        boolean realtime = prediction.source() == TransferArrival.Source.REALTIME;
        boolean exact = prediction.status() == TransferArrival.Status.AVAILABLE;
        return realtime && exact ? Quality.GOOD : Quality.APPROXIMATE;
    }

    private Optional<JourneyLegRouteOption> selectedOption(JourneyLeg leg) {
        if (leg.getSelectedRouteOptionId() == null) {
            return Optional.empty();
        }
        return routeOptionRepository.findById(leg.getSelectedRouteOptionId());
    }

    private boolean hasBusScope(JourneyLegRouteOption option) {
        return option.getRouteId() != null && !option.getRouteId().isBlank()
                && option.getBoardingSeq() != null;
    }
}
