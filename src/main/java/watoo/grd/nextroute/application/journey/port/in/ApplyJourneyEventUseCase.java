package watoo.grd.nextroute.application.journey.port.in;

import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyEventOutcome;

public interface ApplyJourneyEventUseCase {

    /**
     * 여정에 명령 하나를 적용한다.
     *
     * <p>같은 {@code eventId} 로 다시 오면 처음 결과를 그대로 돌려준다. 두 번 반영하지 않는다.
     *
     * @throws watoo.grd.nextroute.global.error.BusinessException
     *         {@code JOURNEY_NOT_FOUND}인 경우
     */
    JourneyEventOutcome apply(Long userId, Long journeyId, JourneyCommand command);
}
