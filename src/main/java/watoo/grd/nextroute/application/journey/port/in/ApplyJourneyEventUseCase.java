package watoo.grd.nextroute.application.journey.port.in;

import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyEventOutcome;

public interface ApplyJourneyEventUseCase {

    /**
     * 여정에 명령 하나를 적용한다.
     *
     * <p>같은 {@code eventId} 로 다시 오면 처음 결과를 그대로 돌려준다. 두 번 반영하지 않는다.
     *
     * @throws java.util.NoSuchElementException 없는 여정이거나 남의 여정
     */
    JourneyEventOutcome apply(Long userId, Long journeyId, JourneyCommand command);
}
