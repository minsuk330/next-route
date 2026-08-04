package watoo.grd.nextroute.application.journey.port.in;

import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;

/**
 * 진행 중인 여정의 안내 lease 를 연장한다.
 *
 * <p>상태를 바꾸는 명령이 아니다. "앱이 아직 살아 있다"는 사실만 전한다.
 */
public interface HeartbeatJourneyUseCase {

    JourneySnapshot heartbeat(Long userId, Long journeyId);
}
