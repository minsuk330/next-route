package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateSummary;
import watoo.grd.nextroute.application.journey.dto.RerouteOrigin;
import watoo.grd.nextroute.application.journey.dto.RerouteProposalView;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 재탐색 제안 응답.
 *
 * <p>제안이 없으면 {@code 204} 가 아니라 {@code proposal: null} 이다. 앱이 "있음/없음"을 한
 * 분기로 다루게 하기 위해서다 — 상태 코드로 가르면 없음이 오류 처리 경로로 새기 쉽다.
 *
 * <p><b>적용 재료({@code apply})는 어느 필드에도 없다.</b> 후보 하나에 경로 원본이 통째로 들어가
 * 후보 3개면 수십 KB 이고, 앱은 그 값을 쓰지 않는다. {@link RerouteProposalView} 를 그대로
 * 반환하지 않는 이유가 이것이다.
 */
public record RerouteProposalResponse(ProposalBody proposal) {

    public static final RerouteProposalResponse EMPTY = new RerouteProposalResponse(null);

    public static RerouteProposalResponse from(Optional<RerouteProposalView> view) {
        return view.map(RerouteProposalResponse::from).orElse(EMPTY);
    }

    public static RerouteProposalResponse from(RerouteProposalView view) {
        return new RerouteProposalResponse(new ProposalBody(
                view.proposalId(), view.routeRevision(), view.origin(), view.kind(),
                view.expiresAt(), view.snapshotObservedAt(),
                // 변환은 이 팩터리 한 자리에서만 한다. 컨트롤러에서 필드를 골라 담게 하면
                // 나중에 필드가 하나 늘 때 적용 재료가 실수로 새어 나간다.
                view.isBoard()
                        ? RerouteCandidateSummary.from(view.candidates(), view.board().originKinds())
                        : RerouteCandidateSummary.from(view.candidates()),
                view.isBoard() ? view.board().version() : null,
                view.isBoard() ? view.board().refreshedAt() : null));
    }

    /**
     * @param origin 이 제안이 가리키는 자리(서울 좌표계). 옛 {@code transferIndex} 를 대신한다
     *               (stage5 2/2) — 그 번호는 leg 경계밖에 못 가리켜 주행 중 정류장에서 내리는
     *               제안을 표현할 수 없었다. 이미 접힌 옛 행을 읽을 때만 null 이다
     * @param boardVersion 보드(#264)면 갱신 순번. 사건 기반 제안은 칸 자체가 없다
     * @param refreshedAt  보드를 마지막으로 찾아본 시각. 후보가 0개면 앱은 "지금 경로가 가장 빨라요"와
     *                     이 시각을 보여 준다
     */
    public record ProposalBody(
            Long proposalId,
            int routeRevision,
            RerouteOrigin origin,
            RerouteProposalKind kind,
            Instant expiresAt,
            Instant snapshotObservedAt,
            List<RerouteCandidateSummary> candidates,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long boardVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant refreshedAt) {
    }
}
