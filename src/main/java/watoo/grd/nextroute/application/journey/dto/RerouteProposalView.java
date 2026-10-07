package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * <b>서버 내부</b> 읽기 모델. 후보의 적용 재료({@code apply})가 그대로 들어 있다.
 *
 * <p>수락 경로(⑤)가 이것을 소비한다. <b>컨트롤러에서 그대로 반환하지 말 것</b> — 적용 재료가
 * 통째로 새어 나가고 응답이 수십 KB 가 된다. API 로 나갈 때는
 * {@link RerouteCandidateSummary#from(List)} 를 지난다.
 *
 * @param baseCommandVersion 만들 당시의 명령 번호. 수락 재검증의 낙관적 락 기준
 * @param origin             서울 좌표계 원점. 이 제안이 가리키는 <b>자리</b>이고 stage5 2/2 부터
 *                           유일한 주소다. 이미 접힌 옛 행을 읽을 때만 null 이다
 * @param board              보드(봉투 v2) 행이면 그 정보. 사건 기반(v1) 행은 null(#264)
 */
public record RerouteProposalView(
        Long proposalId,
        Long journeyId,
        int routeRevision,
        RerouteOrigin origin,
        RerouteProposalKind kind,
        long baseCommandVersion,
        Instant snapshotObservedAt,
        Instant expiresAt,
        List<RerouteCandidate> candidates,
        Board board) {

    /** 사건 기반(v1) 제안. 보드 정보가 없다. */
    public RerouteProposalView(Long proposalId, Long journeyId, int routeRevision, RerouteOrigin origin,
                               RerouteProposalKind kind, long baseCommandVersion,
                               Instant snapshotObservedAt, Instant expiresAt,
                               List<RerouteCandidate> candidates) {
        this(proposalId, journeyId, routeRevision, origin, kind, baseCommandVersion, snapshotObservedAt,
                expiresAt, candidates, null);
    }

    public RerouteProposalView withCandidates(List<RerouteCandidate> alive) {
        return new RerouteProposalView(proposalId, journeyId, routeRevision, origin, kind,
                baseCommandVersion, snapshotObservedAt, expiresAt, alive, board);
    }

    public boolean isBoard() {
        return board != null;
    }

    /**
     * 보드의 읽기 정보(#264).
     *
     * @param version     갱신 순번. 앱 ETag 의 재료 중 하나
     * @param refreshedAt 마지막으로 찾아본 시각. 후보가 0개여도 "찾아봤다"를 말한다
     * @param originKinds 보이는 후보 id → 기점 종류. 앱이 말풍선을 어떻게 그릴지 고르는 값
     */
    public record Board(long version, Instant refreshedAt, Map<String, RerouteOriginKind> originKinds) {
    }
}
