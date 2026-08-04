package watoo.grd.nextroute.application.journey.port.in;

import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;

public interface StartJourneyUseCase {

    /**
     * 검색 결과 하나로 여정을 시작한다.
     *
     * @throws watoo.grd.nextroute.global.error.BusinessException
     *         {@code SNAPSHOT_EXPIRED}, {@code SNAPSHOT_OWNER_MISMATCH}, {@code JOURNEY_COMPILE_FAILED}
     * @throws watoo.grd.nextroute.application.journey.exception.ActiveJourneyExistsException 진행 중인 여정이 있고 {@code replace=false}
     */
    JourneySnapshot start(Long userId, StartJourneyCommand command);
}
