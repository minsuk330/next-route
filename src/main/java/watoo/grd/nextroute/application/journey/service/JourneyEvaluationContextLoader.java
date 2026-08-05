package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 평가에 필요한 상태를 한 번의 읽기 트랜잭션으로 가져온다.
 *
 * <p>별도 빈으로 둔 이유가 하나뿐이다. 같은 클래스 안에서 부르면 스프링 프록시를 지나지 않아
 * {@code @Transactional} 이 조용히 무효가 된다 — 두 조회가 각각 다른 트랜잭션에서 일어나고,
 * 그 사이에 상태가 바뀌면 앞뒤가 어긋난 상태로 계산하게 된다.
 */
@Component
@RequiredArgsConstructor
public class JourneyEvaluationContextLoader {

    private final JourneySessionRepository sessionRepository;
    private final TransferConnectionRepository transferRepository;

    /**
     * @return 평가할 상태가 아니면 빈 값(이미 끝난 여정, 지워진 여정)
     */
    @Transactional(readOnly = true)
    public Optional<JourneyEvaluationContext> load(Long journeyId) {
        JourneySession journey = sessionRepository.findById(journeyId).orElse(null);
        if (journey == null || journey.getStatus() != JourneyStatus.ACTIVE
                || journey.getDeletedAt() != null) {
            return Optional.empty();
        }
        Map<Integer, TransferRiskAssessment> previous = transferRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                        journeyId, journey.getRouteRevision())
                .stream()
                .collect(Collectors.toMap(TransferConnection::getTransferIndex,
                        TransferAssessmentMapper::fromStored));
        return Optional.of(new JourneyEvaluationContext(journey.getId(), journey.getUserId(),
                journey.getRouteRevision(), previous));
    }

    /**
     * 계산에 쓰는 상태 묶음.
     *
     * @param previousAssessments 직전 판정. 값이 달라졌는지 견주는 데만 쓴다
     */
    public record JourneyEvaluationContext(
            Long journeyId,
            Long userId,
            int routeRevision,
            Map<Integer, TransferRiskAssessment> previousAssessments
    ) {
    }
}
