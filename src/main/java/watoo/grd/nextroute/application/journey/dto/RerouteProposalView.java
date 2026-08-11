package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;

import java.time.Instant;
import java.util.List;

/**
 * <b>서버 내부</b> 읽기 모델. 후보의 적용 재료({@code apply})가 그대로 들어 있다.
 *
 * <p>수락 경로(⑤)가 이것을 소비한다. <b>컨트롤러에서 그대로 반환하지 말 것</b> — 적용 재료가
 * 통째로 새어 나가고 응답이 수십 KB 가 된다. API 로 나갈 때는
 * {@link RerouteCandidateSummary#from(List)} 를 지난다.
 *
 * @param baseCommandVersion 만들 당시의 명령 번호. 수락 재검증의 낙관적 락 기준
 */
public record RerouteProposalView(
        Long proposalId,
        Long journeyId,
        int routeRevision,
        int transferIndex,
        RerouteProposalKind kind,
        long baseCommandVersion,
        Instant snapshotObservedAt,
        Instant expiresAt,
        List<RerouteCandidate> candidates) {
}
