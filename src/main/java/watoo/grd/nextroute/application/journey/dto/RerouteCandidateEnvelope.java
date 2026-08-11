package watoo.grd.nextroute.application.journey.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * {@code reroute_proposal.candidates} 컬럼에 담기는 모양.
 *
 * <p>배열을 바로 넣지 않는 이유 둘.
 * <ul>
 *   <li>목록 밖 메타(평가 근거 등)를 붙일 자리가 없어진다</li>
 *   <li>{@link #version} 이 없으면 계약이 바뀔 때 읽는 쪽이 옛 행을 가려낼 수 없다 — 모르는
 *       모양을 새 계약으로 읽어 조용히 어긋난 값을 쓰게 된다</li>
 * </ul>
 *
 * @param version 계약 판. 모르는 값이면 읽는 쪽이 그 행을 쓰지 않는다
 */
public record RerouteCandidateEnvelope(int version, List<RerouteCandidate> candidates) {

    /** 현재 계약 판. 후보의 필드 뜻이 바뀌면 올린다. */
    public static final int CURRENT_VERSION = 1;

    public static RerouteCandidateEnvelope of(List<RerouteCandidate> candidates) {
        return new RerouteCandidateEnvelope(CURRENT_VERSION, candidates);
    }

    /** {@code isX()} 는 Jackson 이 속성으로 오해한다. 봉투에 없는 칸이 생기면 왕복이 깨진다. */
    @JsonIgnore
    public boolean isCurrentVersion() {
        return version == CURRENT_VERSION;
    }
}
