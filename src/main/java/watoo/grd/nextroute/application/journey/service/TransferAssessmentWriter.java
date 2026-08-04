package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 환승 판정 결과만 짧은 트랜잭션에서 저장한다.
 *
 * <p>계산과 저장을 굳이 나눈 이유가 있다. 환승 판정에는 실시간 도착 조회가 들어가는데, 그것을
 * 트랜잭션 안에서 하면 <b>외부 API 가 느린 동안 DB 커넥션을 붙잡고 있다</b>. 커넥션 풀은 여정
 * 수보다 훨씬 작아서, 이 조합은 느린 provider 하나로 전체 요청을 막는다.
 *
 * <p>별도 빈으로 둔 것도 의도다. 같은 클래스 안에서 부르면 프록시를 지나지 않아
 * {@code @Transactional} 이 걸리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TransferAssessmentWriter {

    private final TransferConnectionRepository transferRepository;

    /**
     * 판정을 해당 환승 행에 반영한다.
     *
     * <p>저장 시점에 다시 읽는다. 계산하는 동안 재탐색으로 환승이 교체됐을 수 있어, 계산에 쓴
     * 엔티티를 그대로 쓰면 이미 버려진 행을 되살린다.
     */
    @Transactional
    public void write(Long journeyId, int routeRevision, List<TransferRiskAssessment> assessments) {
        if (assessments.isEmpty()) {
            return;
        }
        Map<Integer, TransferConnection> byIndex = transferRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                        journeyId, routeRevision)
                .stream()
                .collect(Collectors.toMap(TransferConnection::getTransferIndex, Function.identity()));

        for (TransferRiskAssessment assessment : assessments) {
            TransferConnection transfer = byIndex.get(assessment.transferIndex());
            if (transfer == null) {
                continue;
            }
            transfer.applyAssessment(
                    assessment.status(),
                    assessment.currentLegArrivalAt(),
                    assessment.userReadyAt(),
                    assessment.nextBusArrivalAt(),
                    assessment.transferSlackSeconds() == null
                            ? null : Math.toIntExact(assessment.transferSlackSeconds()),
                    TransferRiskEvaluator.SOURCE,
                    assessment.confidence().name(),
                    assessment.calculatedAt(),
                    assessment.currentLegArrivalAt());
        }
    }
}
