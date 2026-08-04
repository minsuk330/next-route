package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.dto.JourneyEvaluationOutcome;
import watoo.grd.nextroute.application.journey.dto.TransferTargetArrival;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Instant;
import java.util.Map;

/**
 * 평가 결과를 저장한다. <b>그 전에 이 여정이 아직 내 것인지 확인한다.</b>
 *
 * <p>확인이 필요한 이유. 평가기는 여정을 선점할 때 다음 평가 시각을 60초 뒤로 밀어 둔다. 어떤
 * 워커가 그보다 느리면(외부 API 가 오래 걸리거나 컨테이너가 잠깐 멈추면) 그 사이 lease 가 만료돼
 * 다른 워커가 같은 여정을 가져가 새로 계산한다. 그때 느렸던 워커가 뒤늦게 저장하면 <b>낡은 판정이
 * 최신 판정을 덮는다.</b> 조건부 UPDATE 한 줄이 그것을 막는다.
 *
 * <p>확인과 저장을 <b>한 트랜잭션</b>에 둔다. 나누면 확인은 통과했는데 저장만 실패한 상태가
 * 생기고, 그때 여정은 이미 30초 뒤로 밀려 있어 그 사이 조회는 갱신되지 않은 판정을 읽는다. 둘 다
 * DB 만 만지는 짧은 작업이라 한 트랜잭션으로 묶어도 외부 호출을 붙잡지 않는다.
 *
 * <p>별도 빈으로 둔 것도 의도다. 같은 클래스 안에서 부르면 프록시를 지나지 않아
 * {@code @Transactional} 이 걸리지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JourneyEvaluationScheduleWriter {

    private final JourneySessionRepository sessionRepository;
    private final JourneyLegRepository legRepository;
    private final TransferAssessmentWriter assessmentWriter;

    /**
     * 소유권을 확인하고, 확인되면 판정과 예측을 저장한다.
     *
     * @param claim             선점 정보. {@code leaseUntil} 이 소유권 증서다
     * @param nextEvaluationAt  다음에 평가할 시각
     * @return 저장했으면 true. 소유권을 잃어 결과를 버렸으면 false
     */
    @Transactional
    public boolean finish(JourneyDueClaim claim, JourneyEvaluationOutcome outcome,
                          Instant nextEvaluationAt) {
        int owned = sessionRepository.finishEvaluation(
                claim.journeyId(), claim.leaseUntil(), nextEvaluationAt, outcome.changed());
        if (owned == 0) {
            // 정상적으로 일어나는 일이다. lease 가 만료돼 다른 워커가 가져갔거나, 단계 전이가
            // 다음 평가를 앞당겼거나, 여정이 끝났다.
            log.debug("[JourneyEvaluation] 소유권 없음 — journeyId={}, 결과 버림", claim.journeyId());
            return false;
        }

        assessmentWriter.write(outcome.journeyId(), outcome.routeRevision(),
                outcome.batch().assessments());
        storeBoardingPredictions(outcome);
        return true;
    }

    /**
     * 대기 단계 행에 도착 예측을 적어 둔다.
     *
     * <p>조회 요청이 실시간 API 를 부르지 않고 이 값을 읽게 하려는 준비다. 저장 시점에 단계를 다시
     * 읽는다 — 계산하는 동안 재탐색으로 단계가 교체됐을 수 있고, 그때 계산에 쓴 엔티티를 그대로
     * 쓰면 이미 버려진 행을 되살린다.
     */
    private void storeBoardingPredictions(JourneyEvaluationOutcome outcome) {
        Map<Integer, TransferTargetArrival> boarding = outcome.batch().boardingByLegIndex();
        if (boarding.isEmpty()) {
            return;
        }
        for (JourneyLeg leg : legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                outcome.journeyId(), outcome.routeRevision())) {
            TransferTargetArrival target = boarding.get(leg.getLegIndex());
            if (target == null) {
                continue;
            }
            CurrentLegArrival arrival = target.arrival();
            leg.applyBoardingPrediction(
                    arrival.arrivalAt(),
                    arrival.source() == null ? null : arrival.source().name(),
                    arrival.quality().name(),
                    arrival.observedAt());
        }
    }
}
