package watoo.grd.nextroute.application.journey.port.in;

import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;

/**
 * 재탐색 제안 수락(Phase 6 ⑤).
 *
 * <p>여정 이벤트({@code ApplyJourneyEventUseCase})와 통로를 나눈 이유는 성질이 다르기 때문이다.
 * 이벤트는 <b>앱이 관측한 사실</b>(단계 전이·취소)이고 수락은 <b>사용자의 선택</b>이다. 고른
 * 대상이 있고, 서버가 그 순간 되짚어야 할 조건이 넷이다.
 */
public interface AcceptRerouteProposalUseCase {

    /**
     * @param candidateId 사용자가 고른 후보. <b>서버가 임의로 고르지 않는다</b>
     * @return 새 개정본이 반영된 여정 상태. 앱은 {@code routeRevision} 으로 geometry 를 다시 받는다
     */
    JourneySnapshot accept(Long userId, Long journeyId, Long proposalId, String candidateId);
}
