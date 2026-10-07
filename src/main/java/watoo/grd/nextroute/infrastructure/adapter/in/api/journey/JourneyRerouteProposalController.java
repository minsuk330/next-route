package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateRoute;
import watoo.grd.nextroute.application.journey.dto.RerouteDecision;
import watoo.grd.nextroute.application.journey.port.in.AcceptRerouteProposalUseCase;
import watoo.grd.nextroute.application.journey.port.in.GetRerouteCandidateRouteUseCase;
import watoo.grd.nextroute.application.journey.service.RerouteProposalService;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalStatus;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.AcceptRerouteProposalRequest;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.CreateRerouteProposalRequest;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.RejectRerouteProposalRequest;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.RerouteProposalDecisionResponse;
import watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto.RerouteProposalResponse;

/**
 * 재탐색 제안 조회·요청.
 *
 * <p>사용자 ID 는 JWT 에서만 온다. 본문에 있어도 쓰지 않는다.
 *
 * <p>이 PR 만으로는 아무것도 보이지 않는다 — 트리거(①)와 후보 생성기(④)가 없어 조회는 항상
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
    private final GetRerouteCandidateRouteUseCase candidateRouteUseCase;
    private final ObjectMapper objectMapper;

    /**
     * 살아 있는 제안. 없으면 body 의 {@code proposal} 이 null 이다({@code 204} 가 아니다).
     *
     * <p><b>보드(#264)면 ETag 를 붙인다.</b> 앱은 화면을 켜 둔 동안 15초마다 부르고, 바뀐 게 없으면
     * {@code 304} 로 끝난다. ETag 는 {@code board_version} 이 아니라 <b>실제로 내보낸 본문</b>의 해시다 —
     * 곧 닿는 자리는 읽는 시각에 빠지므로, 버전이 그대로여도 내용이 바뀔 수 있다. 사건 기반 제안은 지금과
     * 같다(ETag 없음).
     */
    @GetMapping("/{journeyId}/reroute-proposals/active")
    public ResponseEntity<RerouteProposalResponse> active(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        RerouteProposalResponse body =
                RerouteProposalResponse.from(proposalService.findActive(userId, journeyId));
        if (body.proposal() == null || body.proposal().boardVersion() == null) {
            return ResponseEntity.ok(body);
        }
        String etag = etagOf(body);
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(body);
    }

    private String etagOf(RerouteProposalResponse body) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(body));
            return "\"" + java.util.HexFormat.of().formatHex(digest, 0, 16) + "\"";
        } catch (JsonProcessingException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("ETag 를 만들지 못했다", e);
        }
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
     * 후보 하나의 경로(#259). 앱이 지도 말풍선을 누를 때 부른다.
     *
     * <p>{@code path} 는 수락하면 적용될 값 그대로다 — 다시 검색하지 않는다. 모양은
     * {@code /api/route/search} 의 {@code paths[]} 원소와 같다. 지금 타는 구간은 {@code ride} 로
     * 따로 나간다.
     *
     * <p>제안이 낡았으면 {@code 409 REROUTE_PROPOSAL_STALE} 이다. 앱은 목록을 새로 받는다.
     */
    @GetMapping("/{journeyId}/reroute-proposals/{proposalId}/candidates/{candidateId}/route")
    public RerouteCandidateRoute candidateRoute(@AuthenticationPrincipal Long userId,
                                                @PathVariable Long journeyId,
                                                @PathVariable Long proposalId,
                                                @PathVariable String candidateId) {
        return candidateRouteUseCase.get(userId, journeyId, proposalId, candidateId);
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

    /**
     * 사용자가 제안을 <b>거절한다</b>(PR-3 알림 계약).
     *
     * <p>거절은 의사표시다 — 기본값은 "이 여정에서는 이제 그만"이고, 본문의
     * {@code keepAlerts} 를 켜면 다음 제안은 계속 받는다.
     *
     * <p><b>멱등이다.</b> 같은 요청이 두 번 와도 {@code 200} 이고 상태는 한 번만 바뀐다. 오류로
     * 만들면 네트워크가 흔들릴 때 앱이 재시도를 돌다가 사용자에게 실패를 보여 준다.
     */
    @PostMapping("/{journeyId}/reroute-proposals/{proposalId}/reject")
    public RerouteProposalDecisionResponse reject(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long journeyId,
            @PathVariable Long proposalId,
            @RequestBody(required = false) RejectRerouteProposalRequest request) {
        boolean keepAlerts = request != null && request.keepAlertsOrDefault();
        return toResponse(proposalService.decide(userId, journeyId, proposalId,
                RerouteProposalStatus.REJECTED, keepAlerts));
    }

    /**
     * 사용자가 제안을 <b>그냥 닫았다</b>(무시).
     *
     * <p>거절과 다르다. 아무 말도 하지 않은 것이라 여정의 알림을 끝내지 않고, 대신 무응답으로
     * 세어 상한(여정당 2회)까지만 참는다. 앱이 알림 화면을 스와이프로 닫을 때 부르는 자리다.
     */
    @PostMapping("/{journeyId}/reroute-proposals/{proposalId}/dismiss")
    public RerouteProposalDecisionResponse dismiss(@AuthenticationPrincipal Long userId,
                                                   @PathVariable Long journeyId,
                                                   @PathVariable Long proposalId) {
        return toResponse(proposalService.decide(userId, journeyId, proposalId,
                RerouteProposalStatus.DISMISSED, false));
    }

    private RerouteProposalDecisionResponse toResponse(RerouteDecision decision) {
        return new RerouteProposalDecisionResponse(
                decision.closed(), decision.terminated(), decision.optIn());
    }
}
