package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.port.in.AcceptRerouteProposalUseCase;
import watoo.grd.nextroute.application.journey.service.RerouteProposalService;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.AcceptRerouteProposalRequest;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.CreateRerouteProposalRequest;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.RerouteProposalResponse;

/**
 * 재탐색 제안 조회·요청.
 *
 * <p>사용자 ID 는 JWT 에서만 온다. 본문에 있어도 쓰지 않는다.
 *
 * <p>이 PR 만으로는 아무것도 보이지 않는다 — 트리거(①)와 후보 생성기(③④)가 없어 조회는 항상
 * {@code proposal: null}, 요청은 {@code 409 REROUTE_NOT_ELIGIBLE} 이다. 그것이 의도한 기본
 * 상태다.
 */
@RestController
@RequestMapping("/api/journeys")
@RequiredArgsConstructor
@Tag(name = "[회원] 여정")
public class JourneyRerouteProposalController {

    private final RerouteProposalService proposalService;
    private final AcceptRerouteProposalUseCase acceptUseCase;

    /** 살아 있는 제안. 없으면 body 의 {@code proposal} 이 null 이다({@code 204} 가 아니다). */
    @GetMapping("/{journeyId}/reroute-proposals/active")
    public RerouteProposalResponse active(@AuthenticationPrincipal Long userId,
                                          @PathVariable Long journeyId) {
        return RerouteProposalResponse.from(proposalService.findActive(userId, journeyId));
    }

    /**
     * 사용자가 "다시 찾아줘"를 누른 경로.
     *
     * <p>명시 요청도 트리거(①)의 window·cooldown 을 통과해야 한다 — <b>버튼 연타가 외부 검색
     * 연타가 되면 안 된다.</b>
     *
     * <p>후보를 찾았지만 나은 것이 없으면 {@code 200} + {@code proposal: null} 이다. 실패가
     * 아니라 정상 결과이고, 오류로 만들면 앱이 재시도를 돌기 시작한다.
     */
    @PostMapping("/{journeyId}/reroute-proposals")
    public RerouteProposalResponse request(@AuthenticationPrincipal Long userId,
                                           @PathVariable Long journeyId,
                                           @Valid @RequestBody CreateRerouteProposalRequest request) {
        return RerouteProposalResponse.from(
                proposalService.request(userId, journeyId, request.transferIndexOrDefault()));
    }

    /**
     * 사용자가 고른 제안을 실제로 적용한다(⑤).
     *
     * <p>여정 이벤트 통로로 받지 않는다. 이벤트는 <b>앱이 관측한 사실</b>이고 수락은
     * <b>사용자의 선택</b>이라, 재전송 규칙도 되짚어야 할 조건도 다르다.
     *
     * <p>응답은 새 개정본이 반영된 여정 스냅샷이다. 앱은 그 {@code routeRevision} 으로 geometry 를
     * 다시 받아 지도와 진행 엔진을 한꺼번에 갈아끼운다.
     *
     * <p>제안이 만들어진 뒤 사용자가 누르기까지 최대 2분이 있어, 그 사이 여정이 움직였으면
     * {@code 409 REROUTE_PROPOSAL_STALE} 이다. 같은 후보로 다시 누른 재전송은 {@code 200} 이다.
     */
    @PostMapping("/{journeyId}/reroute-proposals/{proposalId}/accept")
    public JourneySnapshot accept(@AuthenticationPrincipal Long userId,
                                  @PathVariable Long journeyId,
                                  @PathVariable Long proposalId,
                                  @Valid @RequestBody AcceptRerouteProposalRequest request) {
        return acceptUseCase.accept(userId, journeyId, proposalId, request.candidateId());
    }
}
