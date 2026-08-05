package watoo.grd.nextroute.application.journey.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 평가기 관측 지표.
 *
 * <p>모든 tag 는 고정 집합이다. 여정 번호·정류장 ID 처럼 요청마다 달라지는 값을 tag 로 쓰면
 * 시계열이 무한히 늘어난다({@code RouteSearchMetrics} 와 같은 관례).
 *
 * <p>가장 중요한 지표는 {@link #PREDICTION} 이다. 실시간으로 잡은 비율과 모델로 내려간 비율을
 * 보여 준다 — 모델 품질을 판단하고, 실시간 조회가 제대로 붙는지 확인하는 근거다.
 */
@Component
@RequiredArgsConstructor
public class JourneyEvaluationMetrics {

    public static final String DURATION = "journey.evaluation.duration";
    public static final String CLAIMED = "journey.evaluation.claimed";
    public static final String FAILURES = "journey.evaluation.failures";
    public static final String LOST_CLAIM = "journey.evaluation.lost-claim";
    public static final String PREDICTION = "journey.transfer.prediction";
    public static final String INTERVAL = "journey.evaluation.interval";

    private final MeterRegistry registry;

    /** 여정 1건 평가 소요와 결말. */
    public void recordEvaluation(String outcome, long nanos) {
        Timer.builder(DURATION).tag("outcome", outcome).register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /** 이번 tick 에 선점한 여정 수. */
    public void recordClaimed(int count) {
        if (count > 0) {
            registry.counter(CLAIMED).increment(count);
        }
    }

    /** 격리된 실패. 사유는 재시도 간격을 가르는 종류와 같다. */
    public void recordFailure(String reason) {
        registry.counter(FAILURES, "reason", reason).increment();
    }

    /** 소유권을 잃어 결과를 버린 횟수. 늘어나면 lease·배치 상한 설정이 잘못됐다는 신호다. */
    public void recordLostClaim() {
        registry.counter(LOST_CLAIM).increment();
    }

    /**
     * 다음 평가까지 얼마를 기다리기로 했는지, 그리고 그렇게 정한 근거.
     *
     * <p>운영이 봐야 하는 것은 근거별 분포다. {@code near}·{@code at_risk} 비율이 높으면 평가기가
     * 바쁘게 돌고 있다는 뜻이고, {@code backoff} 가 대부분이면 대상 여정이 조용하다는 뜻이다.
     *
     * @param reason  {@code JourneyEvaluationIntervalPolicy.Reason} 이름(소문자). 고정 집합이다
     */
    public void recordInterval(String reason, long seconds) {
        registry.summary(INTERVAL, "reason", reason).record(seconds);
    }

    /**
     * 갈아탈 차량 도착을 무엇으로 알아냈는지 센다.
     *
     * <p>"모름"도 함께 센다. 실시간도 모델도 답을 못 준 비율을 알아야 예측이 닿는 구간이 얼마나
     * 되는지 판단할 수 있다.
     */
    public void recordPredictions(List<TransferRiskAssessment> assessments) {
        for (TransferRiskAssessment assessment : assessments) {
            registry.counter(PREDICTION, "source", tagOf(assessment.targetSource())).increment();
        }
    }

    private static String tagOf(CurrentLegArrival.Source source) {
        if (source == CurrentLegArrival.Source.REALTIME_ARRIVAL) {
            return "realtime";
        }
        if (source == CurrentLegArrival.Source.MODEL_ARRIVAL) {
            return "model";
        }
        return "none";
    }
}
