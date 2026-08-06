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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
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
                journey.getRouteRevision(), journey.getCurrentLegIndex(), previous));
    }

    /**
     * 계산에 쓰는 상태 묶음.
     *
     * @param currentLegIndex     지금 진행 중인 단계. 이미 지나온 환승을 간격 계산에서 빼는 데 쓴다
     * @param previousAssessments 직전 판정. 값이 달라졌는지 견주고, 직전 평가 시각을 읽는 데 쓴다
     */
    public record JourneyEvaluationContext(
            Long journeyId,
            Long userId,
            int routeRevision,
            int currentLegIndex,
            Map<Integer, TransferRiskAssessment> previousAssessments
    ) {

        /**
         * 직전 평가와 지금 사이의 간격. 판정을 저장할 때마다 갱신되는 계산 시각에서 되읽는다.
         *
         * <p>따로 저장하지 않는 이유 — 이미 저장돼 있는 값이라 컬럼을 늘릴 필요가 없다.
         *
         * @return 직전 평가 기록이 없으면 null
         */
        public Duration previousInterval(Instant now) {
            return previousAssessments.values().stream()
                    .map(TransferRiskAssessment::calculatedAt)
                    .filter(Objects::nonNull)
                    .max(Instant::compareTo)
                    .map(lastAt -> Duration.between(lastAt, now))
                    .orElse(null);
        }
    }
}
