package watoo.grd.nextroute.application.journey.port.out;

import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteContext;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;

import java.util.List;

/**
 * 재탐색 후보를 만드는 쪽. 사다리의 칸마다 구현이 하나다(③ 노선 후보, ④ 남은 구간 재검색).
 *
 * <p>구현체는 실시간 버스 API·ML·ODsay 를 부르므로 <b>트랜잭션 밖에서</b> 호출된다. 저장은
 * 후보가 다 모인 뒤에 따로 한다.
 *
 * <p>이 PR 에는 구현이 하나도 없다. 그래서 후보가 0개고, 저장도 0행이며, 조회는 항상
 * "제안 없음"이다 — 의도한 기본값이다.
 */
public interface RerouteCandidateProvider {

    RerouteProposalKind kind();

    /**
     * 낮을수록 먼저 본다. {@code ROUTE_OPTION}=0, {@code REMAINING_PATH}=1.
     *
     * <p>순서를 뒤집으면 요금이 폭발한다 — 같은 정류장의 다른 노선으로 해결되는 상황에서도
     * 매번 경로를 통째로 다시 검색하게 된다.
     */
    int priority();

    /**
     * 후보가 없으면 <b>빈 목록</b>이다. 예외를 던지지 않는다 — 재탐색 실패가 여정을 흔들면 안 된다.
     */
    List<RerouteCandidate> findCandidates(RerouteContext context);
}
