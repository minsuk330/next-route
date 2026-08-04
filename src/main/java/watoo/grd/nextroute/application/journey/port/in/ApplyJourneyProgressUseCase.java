package watoo.grd.nextroute.application.journey.port.in;

import watoo.grd.nextroute.application.journey.dto.JourneyProgressCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyProgressOutcome;

/** 앱이 확정한 진행 요약을 여정에 반영한다. */
public interface ApplyJourneyProgressUseCase {

    /**
     * @param userId  인증에서 판별한 소유자. 클라이언트 입력을 신뢰하지 않는다
     * @throws watoo.grd.nextroute.global.error.BusinessException
     *         없는 여정·남의 여정({@code JOURNEY_NOT_FOUND}), 진행 중이 아닌 여정
     *         ({@code JOURNEY_NOT_ACTIVE}), 이전 개정본이나 잘못된 단계
     * @throws watoo.grd.nextroute.application.journey.exception.ProgressTimestampConflictException
     *         같은 관측 시각에 다른 내용이 올 때
     */
    JourneyProgressOutcome apply(Long userId, Long journeyId, JourneyProgressCommand command);
}
