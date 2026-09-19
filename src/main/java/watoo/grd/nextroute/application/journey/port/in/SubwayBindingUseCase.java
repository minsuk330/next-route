package watoo.grd.nextroute.application.journey.port.in;

import watoo.grd.nextroute.application.journey.dto.SubwayBindingView;
import watoo.grd.nextroute.application.journey.dto.SubwayBoardingEvidence;

/** 지하철 탑승 관측을 기록하고 사용자의 수동 보정을 반영한다. */
public interface SubwayBindingUseCase {
    SubwayBindingView submit(Long userId, Long journeyId, int routeRevision, int legIndex,
                             SubwayBoardingEvidence evidence);

    SubwayBindingView reset(Long userId, Long journeyId, int routeRevision, int legIndex);

    SubwayBindingView notBoarded(Long userId, Long journeyId, int routeRevision, int legIndex);
}
