package watoo.grd.nextroute.application.journey.port.in;

import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;

import java.util.Optional;

public interface GetActiveJourneyUseCase {

    /** 진행 중인 여정. 앱이 재실행됐을 때 화면을 복원하는 경로다. */
    Optional<JourneySnapshot> getActive(Long userId);
}
