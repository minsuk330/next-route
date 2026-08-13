package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.port.in.AcceptRerouteProposalUseCase;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

/**
 * 사용자가 고른 제안을 받는 진입점.
 *
 * <p>일 자체는 {@link RerouteAcceptTxService} 가 한 트랜잭션에서 한다. 여기서는 <b>기능 스위치</b>와
 * 인자 확인만 본다 — 스위치를 트랜잭션 안에 두면 꺼져 있는데도 여정 행을 잠그게 된다.
 *
 * <p><b>{@code candidateId} 가 없으면 거절한다.</b> 서버가 임의로 1번 후보를 적용하지 않는다 —
 * 사용자가 고르지 않은 경로로 여정이 바뀌는 것이 이 기능에서 가장 나쁜 사고다.
 */
@Service
@RequiredArgsConstructor
public class RerouteAcceptService implements AcceptRerouteProposalUseCase {

    private final RerouteAcceptTxService txService;
    private final JourneyRerouteProperties properties;

    @Override
    public JourneySnapshot accept(Long userId, Long journeyId, Long proposalId,
                                  String candidateId) {
        if (!properties.isEnabled()) {
            throw new BusinessException(JourneyErrorCode.REROUTE_NOT_ELIGIBLE);
        }
        if (candidateId == null || candidateId.isBlank()) {
            throw new BusinessException(JourneyErrorCode.REROUTE_CANDIDATE_NOT_FOUND);
        }
        return txService.accept(userId, journeyId, proposalId, candidateId);
    }
}
