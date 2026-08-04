package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.dto.TransferTargetArrival;
import watoo.grd.nextroute.application.journey.port.out.TransferTargetArrivalPredictor;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;

import java.time.Instant;
import java.util.List;

/**
 * 단계 종류에 맞는 환승 예측기를 고른다.
 *
 * <p>맞는 구현이 없으면 억지로 숫자를 만들지 않고 모른다고 말한다. 기다리는 단계가 아닌 곳으로
 * 환승 대상이 잡히는 상황(쪼개기 오류)에서는 답할 것이 없다.
 */
@Service
@RequiredArgsConstructor
public class TransferTargetArrivalService {

    private final List<TransferTargetArrivalPredictor> predictors;

    /**
     * @param boardingAnchorAt 사용자가 그 정류장에서 탈 준비를 마치는 시각. 모르면 null
     */
    public TransferTargetArrival predictBoarding(JourneyLeg waitLeg, Instant boardingAnchorAt, Instant now) {
        if (waitLeg == null) {
            return TransferTargetArrival.unknown();
        }
        return predictors.stream()
                .filter(predictor -> predictor.supports(waitLeg.getType()))
                .findFirst()
                .map(predictor -> predictor.predictBoarding(waitLeg, boardingAnchorAt, now))
                .orElseGet(TransferTargetArrival::unknown);
    }
}
