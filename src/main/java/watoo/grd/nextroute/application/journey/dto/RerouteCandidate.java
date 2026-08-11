package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;

import java.util.Objects;

/**
 * 재탐색 후보 하나. <b>두 겹이다</b> — 적용 재료({@link #apply})와 화면 요약({@link #display}).
 *
 * <p>화면 요약만 저장하면 사용자가 다른 경로를 골랐을 때 새 leg 를 만들 재료가 없어 수락 시점에
 * 다시 검색해야 한다. 그 시점에 다른 결과가 나오면 <b>사용자가 고른 것과 다른 경로</b>로 여정이
 * 바뀐다.
 *
 * @param candidateId 저장 시점에 부여하는 UUID. <b>순번이 아니다</b> — 정렬 기준이 바뀌거나
 *                    후보 하나가 걸러지면 순번은 다른 경로를 가리킨다. 만드는 쪽(provider)은
 *                    비워 두고 저장 쪽이 채운다
 */
public record RerouteCandidate(
        String candidateId,
        RerouteProposalKind kind,
        RerouteCandidateApply apply,
        RerouteCandidateDisplay display) {

    /**
     * {@code apply} 가 빠진 후보는 만들 수 없다. 그런 후보는 저장돼도 수락할 수 없어, 사용자가
     * 고를 수 있는 것처럼 보이고 누르면 실패한다.
     */
    public RerouteCandidate {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(apply, "apply");
        Objects.requireNonNull(display, "display");
    }

    /** 저장 시점에 서버가 ID 를 부여한다. provider 가 무엇을 넣었든 덮어쓴다. */
    public RerouteCandidate withCandidateId(String candidateId) {
        return new RerouteCandidate(candidateId, kind, apply, display);
    }
}
