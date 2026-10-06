package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateEnvelope;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposal;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Instant;

/**
 * 저장된 제안에서 후보 하나를 꺼내고, 그 후보가 아직 유효한지 판정한다.
 *
 * <h2>왜 수락과 조회가 이것을 함께 쓰나</h2>
 * 앱은 후보의 경로를 먼저 <b>조회</b>해 보여 주고, 사용자가 고르면 <b>수락</b>한다(#259). 두 경로가
 * 후보를 각자 읽으면 언젠가 갈라진다 — 그러면 사용자가 본 경로와 다른 경로로 여정이 바뀐다.
 * 그래서 읽기와 유효성 판정을 한 자리에 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerouteCandidateReader {

    private final ObjectMapper objectMapper;

    /**
     * 저장된 후보 중 사용자가 고른 것.
     *
     * <p><b>모르는 봉투 판은 없는 것으로 취급한다.</b> 아는 판은 v1(사건 기반)과 v2(보드)다. 새 계약으로 옛 행을 읽으면 필드 뜻이 어긋난
     * 채 수락까지 흘러가고, 그 결과는 사용자가 고르지 않은 경로로 여정이 바뀌는 것이다.
     *
     * @throws BusinessException {@code REROUTE_CANDIDATE_NOT_FOUND}
     */
    public RerouteCandidate read(RerouteProposal proposal, String candidateId) {
        RerouteCandidateEnvelope envelope;
        try {
            envelope = objectMapper.treeToValue(
                    proposal.getCandidates(), RerouteCandidateEnvelope.class);
        } catch (Exception e) {
            log.warn("[JourneyReroute] 후보를 읽지 못했다 proposalId={}: {}",
                    proposal.getId(), e.getMessage());
            throw new BusinessException(JourneyErrorCode.REROUTE_CANDIDATE_NOT_FOUND);
        }
        if (!envelope.isReadable()) {
            throw new BusinessException(JourneyErrorCode.REROUTE_CANDIDATE_NOT_FOUND);
        }
        // 보드(v2)는 보이는 자리의 후보만 고를 수 있다. 숨긴 자리를 고르게 두면 사용자가 보지
        // 못한 경로가 적용된다.
        return envelope.visibleCandidates().stream()
                .filter(candidate -> candidateId.equals(candidate.candidateId()))
                .findFirst()
                .orElseThrow(() ->
                        new BusinessException(JourneyErrorCode.REROUTE_CANDIDATE_NOT_FOUND));
    }

    /**
     * 제안이 만들어진 뒤 여정이 움직였는가.
     *
     * <p>제안이 만들어진 뒤 사용자가 누르기까지 최대 2분이 있고, 그 사이 여정은 계속 움직인다.
     * 개정본·명령 번호가 바뀌었거나, 만료됐거나, 교체 시작 단계를 이미 지났으면 낡은 것이다.
     * 제안의 상태({@code ACTIVE} 여부)는 여기서 보지 않는다 — 수락은 그것을 원자적 갱신으로 본다.
     */
    public static boolean isStale(JourneySession journey, RerouteProposal proposal,
                                  RerouteCandidate candidate, Instant now) {
        return proposal.getRouteRevision() != journey.getRouteRevision()
                || proposal.getBaseCommandVersion() != journey.getCommandVersion()
                || !proposal.getExpiresAt().isAfter(now)
                || candidate.apply().fromLegIndex() < journey.getCurrentLegIndex();
    }
}
