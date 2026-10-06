package watoo.grd.nextroute.application.journey.port.in;

import watoo.grd.nextroute.application.journey.dto.RerouteCandidateRoute;

/**
 * 재탐색 후보 하나의 경로 조회(#259).
 *
 * <p>제안 응답에 싣지 않는 이유 — 후보 하나에 경로 원본이 통째로 들어가 수십 KB 이고, 사용자는
 * 보통 한두 개만 열어 본다.
 */
public interface GetRerouteCandidateRouteUseCase {

    /**
     * @return 수락하면 적용될 경로와 지금 타는 구간
     * @throws watoo.grd.nextroute.global.error.BusinessException 남의 여정·없는 제안·없는 후보면
     *         404, 제안이 더 이상 유효하지 않으면 {@code 409 REROUTE_PROPOSAL_STALE}
     */
    RerouteCandidateRoute get(Long userId, Long journeyId, Long proposalId, String candidateId);
}
