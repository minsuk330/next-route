package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;

import java.time.Duration;
import java.time.Instant;

/**
 * 조회가 실패했을 때 직전 판정을 잠시 붙잡을지 정한다.
 *
 * <p>외부 API 는 수시로 잠깐씩 막힌다. 그때마다 판정을 "모름"으로 덮으면, 30초 뒤 복구돼도 그 사이
 * 사용자는 근거 없는 화면을 본다. 직전에 계산한 값이 아직 쓸 만하다면 그것을 그대로 두는 편이 낫다.
 *
 * <p><b>언제까지 붙잡을지는 조회 쪽 규칙과 같게 맞춘다.</b> 조회 경로({@code StoredJourneyEvaluationReader})는
 * 가리키는 도착 시각이 지났거나 계산한 지 오래된 판정을 낡았다고 보고 버린다. 여기서 그보다 오래
 * 붙잡으면, 평가기는 값을 유지했는데 조회는 버리는 상태가 되어 아무도 이득을 못 본다.
 *
 * <p>계산만 한다 — DB 도 시계도 직접 만지지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TransferAssessmentGracePolicy {

    private final JourneyEvaluatorProperties properties;

    /**
     * 저장할 판정을 고른다.
     *
     * @param fresh    이번에 계산한 판정
     * @param previous 저장돼 있던 직전 판정. 없으면 null
     * @return 유지하기로 하면 {@code previous}, 아니면 {@code fresh}
     */
    public TransferRiskAssessment choose(TransferRiskAssessment fresh,
                                         TransferRiskAssessment previous, Instant now) {
        if (!fresh.isUnknown() || previous == null || previous.isUnknown()) {
            // 답을 얻었거나, 붙잡을 직전 값이 없다. 유예를 따질 상황이 아니다.
            return fresh;
        }
        if (!stillUsable(previous, now)) {
            return fresh;
        }
        return previous;
    }

    /**
     * 직전 판정이 아직 쓸 만한지.
     *
     * <p>두 가지를 본다. 가리키는 도착 시각이 지나지 않았어야 하고(이미 떠난 버스를 알려 주면 사용자는
     * 오지 않을 차를 기다린다), 계산한 지 유예 시간을 넘지 않았어야 한다.
     */
    private boolean stillUsable(TransferRiskAssessment previous, Instant now) {
        Instant arrivalAt = previous.nextBusArrivalAt();
        if (arrivalAt == null || arrivalAt.isBefore(now)) {
            return false;
        }
        Instant calculatedAt = previous.calculatedAt();
        if (calculatedAt == null) {
            return false;
        }
        Duration age = Duration.between(calculatedAt, now);
        return age.compareTo(properties.unknownGrace()) <= 0;
    }
}
