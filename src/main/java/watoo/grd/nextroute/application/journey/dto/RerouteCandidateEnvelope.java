package watoo.grd.nextroute.application.journey.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

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
 * <h2>두 판이 함께 산다 (#264)</h2>
 * <ul>
 *   <li><b>v1</b> {@code {version:1, candidates}} — 사건 기반 경로(요청·알림)가 쓰는 한 번 찍은 사진</li>
 *   <li><b>v2</b> {@code {version:2, slots}} — 주기 갱신 보드. 자리마다 보이는가·이득·시각을 기억한다</li>
 * </ul>
 * 읽는 쪽은 판 값으로 갈라 {@link #visibleCandidates()} 로 같은 모양을 얻는다. v2 를 "현재 판"으로
 * 올려 v1 을 못 읽게 만들면, 플래그가 꺼져 있어도 기존 경로가 만든 행이 전부 "없음"이 된다 — 조회·
 * 경로 조회·수락이 같이 죽는다. 그래서 기존 경로의 쓰기는 v1 그대로 두고 둘 다 읽는다.
 *
 * @param version    계약 판. 모르는 값이면 읽는 쪽이 그 행을 쓰지 않는다
 * @param candidates v1 의 후보 목록. v2 에서는 없다
 * @param slots      v2 의 자리 목록. v1 에서는 없다 — 직렬화에서 빠지므로 v1 행의 모양은 예전과 같다
 */
public record RerouteCandidateEnvelope(
        int version,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<RerouteCandidate> candidates,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<RerouteBoardSlot> slots) {

    /** 사건 기반 경로가 쓰는 판. 후보의 필드 뜻이 바뀌면 올린다. */
    public static final int CURRENT_VERSION = 1;

    /** 주기 갱신 보드의 판. */
    public static final int BOARD_VERSION = 2;

    public static RerouteCandidateEnvelope of(List<RerouteCandidate> candidates) {
        return new RerouteCandidateEnvelope(CURRENT_VERSION, candidates, null);
    }

    public static RerouteCandidateEnvelope ofBoard(List<RerouteBoardSlot> slots) {
        return new RerouteCandidateEnvelope(BOARD_VERSION, null, slots);
    }

    /** {@code isX()} 는 Jackson 이 속성으로 오해한다. 봉투에 없는 칸이 생기면 왕복이 깨진다. */
    @JsonIgnore
    public boolean isCurrentVersion() {
        return version == CURRENT_VERSION;
    }

    @JsonIgnore
    public boolean isBoard() {
        return version == BOARD_VERSION;
    }

    /** 이 코드가 뜻을 아는 판인가. 아니면 그 행은 없는 것으로 취급한다. */
    @JsonIgnore
    public boolean isReadable() {
        return isCurrentVersion() || isBoard();
    }

    /**
     * 사용자에게 보일 수 있는 후보. v1 은 목록 전체, v2 는 <b>보이는 자리</b>의 후보다.
     *
     * <p>숨긴 자리의 후보를 내보내면 사용자가 보지 못한 경로가 수락될 수 있다.
     */
    @JsonIgnore
    public List<RerouteCandidate> visibleCandidates() {
        if (isBoard()) {
            return slots == null ? List.of() : slots.stream()
                    .filter(RerouteBoardSlot::isVisible)
                    .map(RerouteBoardSlot::candidate)
                    .toList();
        }
        return candidates == null ? List.of() : candidates;
    }
}
