package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.journey.repository.RerouteProposalRepository;

/**
 * 사용자가 보드 후보를 열어 봤다 — 직전 push 에 답한 것으로 적는다(#264 PR-5 무시 집계).
 *
 * <p>후보 경로 조회는 읽기 전용 트랜잭션이라 그 안에서 쓸 수 없다. 그래서 새 트랜잭션으로 연다. 제안 행
 * 하나를 고치는 짧은 쓰기이고, 부르는 쪽은 아무 행도 잠그지 않으므로 잠금 순서 문제가 없다.
 * 예외를 삼킨다 — 기록을 못 해도 사용자가 본 경로는 그대로 보여 줘야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerouteBoardPushAnswerService {

    private final RerouteProposalRepository proposalRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void answered(Long journeyId) {
        try {
            proposalRepository.answerPush(journeyId);
        } catch (RuntimeException e) {
            log.warn("[RerouteBoard] push 응답 기록 실패 journeyId={}: {}", journeyId, e.getMessage());
        }
    }
}
