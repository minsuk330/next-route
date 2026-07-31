package watoo.grd.nextroute.application.journey.port.in;

import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;

import java.util.Optional;

public interface GetJourneyUseCase {

    /**
     * 여정 하나를 번호로 조회한다. 끝난 여정도 볼 수 있다.
     *
     * <p>진행 중인 것만 찾는 {@code getActive} 와 다르다. 앱이 알림을 눌러 들어왔을 때 그 여정이
     * 이미 끝났을 수도 있는데, 그때 "없다"고 답하면 무슨 일이 있었는지 보여 줄 수 없다.
     *
     * <p>남의 여정은 비어 있는 값으로 돌아온다. 존재 여부조차 알려 주지 않는다.
     */
    Optional<JourneySnapshot> get(Long userId, Long journeyId);
}
