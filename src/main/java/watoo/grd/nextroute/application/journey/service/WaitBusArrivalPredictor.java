package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Quality;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Source;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.port.out.CurrentLegArrivalPredictor;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;

import java.time.Instant;
import java.util.Optional;

/**
 * 버스를 기다리는 단계가 언제 끝나는지 — 즉 버스가 언제 오는지.
 *
 * <p>이동 단계와 근거가 완전히 다르다. 이동 중에는 사용자가 어디까지 왔는지가 답이지만, 기다릴
 * 때는 <b>버스가 어디까지 왔는지</b>가 답이다. 그래서 GPS 진행도가 아니라 실시간 도착 정보를 쓴다.
 *
 * <p>검색 결과의 구간 소요시간을 대기시간으로 쓰지 않는다. 그 값에는 대기시간이 들어 있지 않아
 * (SC-9) 그것으로 채우면 근거 없는 숫자가 된다. 실시간도 모델도 답을 못 주면 모른다고 말한다.
 *
 * <p>기존 {@code SingleTransferPredictor} 를 그대로 쓴다. 실시간 우선, 안 되면 ML 로 내려가는
 * 규칙과 provider 차단·예산 초과 판정이 이미 그 안에 있다. 여기서 다시 만들면 두 곳의 판정이
 * 어긋난다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WaitBusArrivalPredictor implements CurrentLegArrivalPredictor {

    private final PredictTransferUseCase predictTransferUseCase;
    private final JourneyLegRouteOptionRepository routeOptionRepository;

    @Override
    public boolean supports(JourneyLegType type) {
        return type == JourneyLegType.WAIT_BUS;
    }

    @Override
    public CurrentLegArrival predict(JourneyLeg leg, LegGeometry geometry, Instant now) {
        // 아직 시작하지 않은 단계는 "사용자가 언제 정류장에 서는지"를 알 수 없다. 그 시각은
        // 앞 단계 ETA 에서 나오고, 그것을 합성하는 일은 환승 준비 시각 계산의 몫이다.
        if (leg.getState() != JourneyLegState.ACTIVE) {
            return CurrentLegArrival.unknown();
        }

        Optional<JourneyLegRouteOption> option = selectedOption(leg);
        if (option.isEmpty() || !hasBusScope(option.get()) || leg.getStartStopId() == null) {
            log.debug("대기 버스 ETA 조회 불가 — legIndex={}, 노선·정류장 미해석", leg.getLegIndex());
            return CurrentLegArrival.unknown();
        }

        TransferPredictionResult prediction = predictTransferUseCase.predict(
                leg.getStartStopId(),
                option.get().getRouteId(),
                option.get().getBoardingSeq(),
                now);

        if (prediction.predictedArrivalAt() == null) {
            return CurrentLegArrival.unknown();
        }
        return new CurrentLegArrival(
                prediction.predictedArrivalAt(),
                (int) Math.max(0, prediction.predictedArrivalAt().getEpochSecond() - now.getEpochSecond()),
                null,
                null,
                Source.REALTIME_ARRIVAL,
                qualityOf(prediction),
                prediction.calculatedAt());
    }

    /**
     * 실시간으로 잡은 차량이면 믿을 만하고, 모델 추정이면 오차가 크다.
     *
     * <p>{@code ARRIVAL_TIME_APPROXIMATE} 는 실시간이어도 도착시간이 근사라는 표시라서 함께
     * 낮춘다.
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
