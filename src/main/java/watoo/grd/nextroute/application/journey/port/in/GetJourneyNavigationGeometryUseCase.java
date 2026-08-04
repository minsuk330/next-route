package watoo.grd.nextroute.application.journey.port.in;

import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry;

/** 여정 전체의 지도·진행 판정용 좌표를 조회한다. */
public interface GetJourneyNavigationGeometryUseCase {

    /**
     * @param userId            인증에서 판별한 소유자. 클라이언트 입력을 신뢰하지 않는다
     * @param journeyId         여정 식별자
     * @param requestedRevision 앱이 보고 있던 경로 개정본. null 이면 현재 개정본을 준다
     * @throws watoo.grd.nextroute.global.error.BusinessException
     *         없는 여정이거나 남의 여정일 때({@code JOURNEY_NOT_FOUND})
     * @throws watoo.grd.nextroute.application.journey.exception.StaleRouteRevisionException
     *         요청 개정본이 현재와 다를 때
     */
    JourneyNavigationGeometry get(Long userId, Long journeyId, Integer requestedRevision);
}
