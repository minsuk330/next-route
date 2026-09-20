package watoo.grd.nextroute.application.journey.port.out;

import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteContext;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;

import java.util.List;

/**
 * 재탐색 후보를 만드는 쪽. 사다리의 칸마다 구현이 하나다.
 *
 * <p>지금 칸은 <b>하나</b>다(④ 남은 구간 재검색). ③(같은 정류장 다른 노선)은 폐기됐다 — ④가
 * 다음 정류장 창으로 그 기능을 흡수했다(journey-reroute-nextstop PR-5). 사다리 구조를 남겨 둔
 * 이유는 칸이 다시 늘 수 있어서가 아니라, {@code priority} 가 <b>요금 순서</b>를 뜻하기 때문이다 —
 * 값싼 칸이 먼저 답하면 비싼 검색을 아예 안 부른다.
 *
 * <p>구현체는 실시간 버스 API·ML·경로 검색을 부르므로 <b>트랜잭션 밖에서</b> 호출된다. 저장은
 * 후보가 다 모인 뒤에 따로 한다.
 *
 * <p>이 PR 에는 구현이 하나도 없다. 그래서 후보가 0개고, 저장도 0행이며, 조회는 항상
 * "제안 없음"이다 — 의도한 기본값이다.
 */
public interface RerouteCandidateProvider {

    RerouteProposalKind kind();

    /**
     * 낮을수록 먼저 본다. 지금은 {@code REMAINING_PATH}=1 하나뿐이다.
     *
     * <p>값이 비어 보여도 낮은 번호를 다시 쓰지 않는다 — 번호는 <b>요금 순서</b>라, 나중에 값싼
     * 칸이 생기면 그 앞자리에 들어가야 한다.
     */
    int priority();

    /**
     * 후보가 없으면 <b>빈 목록</b>이다. 예외를 던지지 않는다 — 재탐색 실패가 여정을 흔들면 안 된다.
     */
    List<RerouteCandidate> findCandidates(RerouteContext context);
}
