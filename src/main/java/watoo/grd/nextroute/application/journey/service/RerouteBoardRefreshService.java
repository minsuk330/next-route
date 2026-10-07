package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 여정 하나의 보드를 다시 찾는다(#264, reroute-periodic-board).
 *
 * <p>PR-3 부터 후보 원천은 공급자의 <b>보드 진입점</b>({@link RemainingPathCandidateProvider#evaluateBoard})이다 —
 * 지금 진행 중인 단계에서 기점을 열어(걷기·기다리기·탑승, 환승 없어도) 자리마다 평가한다. 보일지·숨길지는
 * {@link RerouteBoardMerger} 가 정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerouteBoardRefreshService {

    private final JourneySessionRepository sessionRepository;
    private final RemainingPathCandidateProvider candidateProvider;
    private final RerouteBoardService boardService;
    private final RerouteBoardPushService pushService;
    private final Clock clock;

    /**
     * @return 저장 결과. 끝난 여정이면 비어 있다
     */
    public Optional<RerouteBoardService.Written> refresh(Long journeyId) {
        Optional<JourneySession> found = sessionRepository.findById(journeyId)
                .filter(journey -> journey.getDeletedAt() == null)
                .filter(journey -> journey.getStatus() == JourneyStatus.ACTIVE)
                // 단독 추적은 재탐색이 없다(#265). 선점 SQL 이 이미 거르지만, 사용자 요청 제안이 선점 없이
                // 여기를 직접 부르는 길이 있어 한 번 더 막는다.
                .filter(journey -> journey.getKind() == JourneyKind.NAVIGATION);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        JourneySession journey = found.get();
        Instant now = Instant.now(clock);

        RemainingPathCandidateProvider.BoardResult result = candidateProvider.evaluateBoard(
                new RemainingPathCandidateProvider.BoardRequest(journey.getId(), journey.getUserId(),
                        journey.getRouteRevision(), journey.getCommandVersion(),
                        journey.getCurrentLegIndex(), now));

        Map<String, RerouteBoardMerger.Evaluated> evaluated = new HashMap<>();
        Instant observedAt = now;
        for (RemainingPathCandidateProvider.BoardEvaluation evaluation : result.evaluations()) {
            evaluated.merge(evaluation.slotKey(), toEvaluated(evaluation),
                    (a, b) -> a.gainSec() >= b.gainSec() ? a : b);
            if (evaluation.candidate() != null) {
                Instant candidateObservedAt = evaluation.candidate().display().dataObservedAt();
                if (candidateObservedAt != null && candidateObservedAt.isBefore(observedAt)) {
                    observedAt = candidateObservedAt;
                }
            }
        }
        RerouteBoardService.Written written = boardService.write(new RerouteBoardService.BoardWrite(
                journey.getId(), journey.getUserId(), journey.getRouteRevision(),
                journey.getCommandVersion(), journey.getCurrentLegIndex(), observedAt,
                new RerouteBoardMerger.Input(evaluated, Set.of(),
                        result.known() ? result.offered() : null)), now);
        if (written.stored()) {
            // 조용히 갱신한 뒤, 깨울 만한지 본다(#264 PR-5). 실패해도 갱신은 성공이다.
            pushService.considerPush(journey.getId(), written.slots(), now);
        }
        return Optional.of(written);
    }

    private static RerouteBoardMerger.Evaluated toEvaluated(
            RemainingPathCandidateProvider.BoardEvaluation evaluation) {
        return new RerouteBoardMerger.Evaluated(evaluation.slotKey(), evaluation.originKind(),
                evaluation.candidate(), evaluation.gainSec(), evaluation.realtimeOnly(),
                evaluation.extraWaits(), evaluation.routeFingerprint(), evaluation.alightAt());
    }
}
