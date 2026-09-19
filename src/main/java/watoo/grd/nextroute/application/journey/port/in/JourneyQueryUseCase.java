package watoo.grd.nextroute.application.journey.port.in;

import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;

import java.util.Optional;

/** 사용자의 진행 중 여정 또는 특정 여정을 조회한다. */
public interface JourneyQueryUseCase {
    Optional<JourneySnapshot> getActive(Long userId);

    Optional<JourneySnapshot> get(Long userId, Long journeyId);
}
