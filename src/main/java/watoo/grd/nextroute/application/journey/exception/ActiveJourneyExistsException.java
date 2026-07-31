package watoo.grd.nextroute.application.journey.exception;

import lombok.Getter;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;

/**
 * 이미 진행 중인 여정이 있다.
 *
 * <p>기존 여정을 자동으로 끝내지 않는다. 사용자가 실제로 이동 중일 수 있고, 그 상태를 서버가
 * 말없이 버리면 화면과 알림이 함께 사라진다. 갈아타려면 {@code replace=true} 를 명시해야 한다.
 *
 * <p>현재 여정을 함께 실어 보낸다. 앱이 "지금 진행 중인 여정으로 돌아가기"를 바로 그릴 수 있게
 * 하기 위해서다 — 409 만 받으면 무엇이 진행 중인지 다시 물어야 한다.
 */
@Getter
public class ActiveJourneyExistsException extends RuntimeException {

    private final transient JourneySnapshot current;

    public ActiveJourneyExistsException(JourneySnapshot current) {
        super("이미 진행 중인 여정이 있다");
        this.current = current;
    }
}
