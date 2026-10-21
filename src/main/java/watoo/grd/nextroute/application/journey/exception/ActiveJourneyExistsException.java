package watoo.grd.nextroute.application.journey.exception;

import lombok.Getter;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

/**
 * 이미 진행 중인 여정이 있다.
 *
 * <p>기존 여정을 자동으로 끝내지 않는다. 사용자가 실제로 이동 중일 수 있고, 그 상태를 서버가
 * 말없이 버리면 화면과 알림이 함께 사라진다. 갈아타려면 {@code replace=true} 를 명시해야 한다.
 *
 * <p>현재 여정을 함께 실어 보낸다. 앱이 "지금 진행 중인 여정으로 돌아가기"를 바로 그릴 수 있게
 * 하기 위해서다 — 409 만 받으면 무엇이 진행 중인지 다시 물어야 한다. 이 스냅샷에는 예측({@code eta})이
 * 없다 — 도착 예정은 {@code GET /api/journeys/active} 로 받는다(#475).
 */
@Getter
public class ActiveJourneyExistsException extends BusinessException {

    private final transient JourneySnapshot current;

    public ActiveJourneyExistsException(JourneySnapshot current) {
        super(JourneyErrorCode.ACTIVE_JOURNEY_EXISTS);
        this.current = current;
    }
}
