package watoo.grd.nextroute.application.journey.port.out;

import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.TransferTargetArrival;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Instant;

/**
 * 갈아탈 차량이 <b>사용자가 그 정류장에 닿을 시각</b>에 언제 오는지.
 *
 * <p>{@link CurrentLegArrivalPredictor} 와 용도가 다르다. 그쪽은 "지금 진행 중인 단계가 언제
 * 끝나나"를 답하므로 아직 시작하지 않은 단계에는 답하지 않고, 기준 시각도 늘 지금이다.
 *
 * <p>여기서 답할 것은 아직 오지 않은 미래다. 사용자는 버스를 타고 이동하는 중이고, 알고 싶은 것은
 * "내가 저 정류장에 도착할 때 갈아탈 차가 언제 오나"다. 그래서 기준 시각(앵커)을 밖에서 받는다.
 * 앵커를 지금 시각으로 대신하면 사용자가 도착하기 전에 지나갈 차량을 고르게 된다.
 */
public interface TransferTargetArrivalPredictor {

    boolean supports(JourneyLegType type);

    /**
     * @param waitLeg          갈아탈 차량을 기다리는 단계. 아직 시작하지 않았어도 된다
     * @param boardingAnchorAt 사용자가 그 정류장에서 탈 준비를 마치는 시각
     * @param now              지금. 앵커가 너무 먼 미래인지 판단하는 데 쓴다
     */
    TransferTargetArrival predictBoarding(JourneyLeg waitLeg, Instant boardingAnchorAt, Instant now);
}
