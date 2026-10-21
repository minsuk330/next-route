package watoo.grd.nextroute.application.journey.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;

import java.time.Duration;
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
    public static final String PROVIDER_SLOT = "journey.evaluation.provider-slot";
    public static final String GRACE_HELD = "journey.evaluation.grace-held";
    public static final String BOARDING_MISS = "journey.transfer.boarding-miss";
    public static final String CLAIM_DURATION = "journey.evaluation.claim.duration";
    public static final String TICK_DURATION = "journey.evaluation.tick.duration";
    public static final String START_LAG = "journey.evaluation.start-lag";
    public static final String QUEUE_WAIT = "journey.evaluation.queue-wait";
    public static final String JUDGMENT = "journey.evaluation.judgment";
    public static final String REFILL = "journey.evaluation.refill";
    public static final String WAIT_AHEAD = "journey.evaluation.wait-ahead";

    /*
     * 구간 경계. 기본 Timer 는 횟수·합계·최댓값만 내보내 p95 를 구할 수 없다. 백분위 구간을 통째로
     * 켜면(약 70개) tag 조합마다 시계열이 그만큼 늘어나므로, 판단에 쓰는 경계만 둔다.
     *
     * 지연 계열은 감지 SLO(긴급 10초·주의 30초·여유 2분)와 폴링 상한(180초)을 경계로 넣어
     * "SLO 안에 든 비율"을 그대로 읽게 한다.
     */
    private static final Duration[] LAG_SLO = seconds(1, 5, 10, 30, 60, 120, 180);
    private static final Duration[] EVALUATION_SLO =
            millis(100, 250, 500, 1_000, 1_500, 2_500, 5_000, 10_000);
    private static final Duration[] QUERY_SLO = millis(10, 50, 100, 250, 500, 1_000, 2_500);
    private static final Duration[] TICK_SLO =
            millis(100, 500, 1_000, 2_500, 5_000, 10_000, 30_000, 60_000);

    private final MeterRegistry registry;

    /** 여정 1건 평가 소요와 결말. */
    public void recordEvaluation(String outcome, long nanos) {
        Timer.builder(DURATION).tag("outcome", outcome)
                .serviceLevelObjectives(EVALUATION_SLO)
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 선점 쿼리 한 번의 소요. 만기 여정이 늘면 가장 먼저 커지는 DB 쪽 비용이다.
     */
    public void recordClaimDuration(long nanos) {
        Timer.builder(CLAIM_DURATION)
                .serviceLevelObjectives(QUERY_SLO)
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * tick 한 바퀴(선점 + 배치가 끝나기를 기다린 시간).
     *
     * <p>빈 tick 과 일한 tick 을 가른다. 섞으면 빈 tick 이 대부분이라 분포가 선점 쿼리 시간으로
     * 뭉개진다.
     *
     * @param claimedAny 이번 tick 에 선점한 여정이 있었는지
     */
    public void recordTick(boolean claimedAny, long nanos) {
        Timer.builder(TICK_DURATION).tag("claimed", claimedAny ? "some" : "none")
                .serviceLevelObjectives(TICK_SLO)
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 예정 시각에서 평가 시작까지 늦어진 시간 — 감지 지연에 그대로 더해지는 몫이다.
     *
     * <p>다음 tick 까지의 대기, 선점 순서에서 밀린 시간, 실행기 큐 대기를 모두 포함한다. 이 중 큐
     * 대기만 따로 보려면 {@link #QUEUE_WAIT} 를 본다. 둘의 차이가 선점 쪽 몫이다.
     *
     * <p>선점이 만료돼 다시 잡힌 여정은 예정 시각이 직전 선점 시각이 된다. 그래서 워커가 죽은
     * 경우의 지연은 이 값에 lease 만큼 덜 잡힌다.
     *
     * <p>음수(예정보다 일찍 시작)는 0으로 센다. 선점 조건이 {@code next_evaluation_at <= now} 라
     * 정상이면 나오지 않고, 나오면 시계 차이다.
     */
    public void recordStartLag(Duration lag) {
        Timer.builder(START_LAG)
                .serviceLevelObjectives(LAG_SLO)
                .register(registry)
                .record(lag.isNegative() ? Duration.ZERO : lag);
    }

    /** 실행기에 맡긴 뒤 worker 가 집어 갈 때까지 큐에서 기다린 시간. */
    public void recordQueueWait(long nanos) {
        Timer.builder(QUEUE_WAIT)
                .serviceLevelObjectives(LAG_SLO)
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 빈자리 보충이 무엇 때문에 일어났고 몇 건을 잡았는지. {@code trigger=completion} 이 대부분이면 평가기가
     * tick 박자가 아니라 워커 속도로 돌고 있다는 뜻이다.
     *
     * @param trigger {@code tick}·{@code completion}
     */
    public void recordRefill(String trigger, int claimed) {
        registry.counter(REFILL, "trigger", trigger).increment();
        if (claimed > 0) {
            registry.counter(REFILL + ".claimed", "trigger", trigger).increment(claimed);
        }
    }

    /**
     * 평가를 마친 여정의 판정이 직전과 달라졌는지.
     *
     * <p>{@code changed=false} 비율은 "아무것도 안 바뀌었는데 돈 평가"의 크기다. 이벤트 기반
     * 재평가가 줄일 수 있는 몫이므로, 전환 전후를 비교하는 기준선이 된다.
     */
    public void recordJudgment(boolean changed) {
        registry.counter(JUDGMENT, "changed", Boolean.toString(changed)).increment();
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
     * 평가기 몫의 provider 슬롯을 잡았는지.
     *
     * <p>{@code rejected} 가 늘어나면 평가기가 자기 몫보다 더 부르려 한다는 뜻이다. 워커 스레드를
     * 줄이거나 {@code provider-permits} 를 올릴지 판단하는 근거가 된다.
     *
     * @param result acquired · rejected · interrupted
     * @param gate   bus(버스 단계가 있는 여정, 한도 있는 공급자) · subway(그 밖) — #427 처방 2차
     */
    public void recordProviderSlot(String result, String gate) {
        registry.counter(PROVIDER_SLOT, "result", result, "gate", gate).increment();
    }

    /**
     * 조회에 실패해 직전 판정을 그대로 둔 횟수.
     *
     * <p>이 값이 크면 provider 가 자주 막히고 있다는 뜻이다. 사용자에게는 옛 값이 보이므로,
     * 계속 늘어나면 유예 시간이 아니라 provider 쪽을 봐야 한다.
     */
    public void recordGraceHeld() {
        registry.counter(GRACE_HELD).increment();
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

    /**
     * 승차 시각을 못 구한 이유.
     *
     * <p>{@link #PREDICTION} 의 {@code source=none} 은 "못 구했다"까지만 말한다. 그 하나로는
     * 막혀서 못 물어본 것인지, 물어봤는데 탈 차가 없던 것인지, 애초에 정류장·노선이 안 풀려
     * 물어보지도 않은 것인지 가릴 수 없다 — 셋은 고쳐야 할 곳이 서로 다르다.
     *
     * <p>{@code reason} 은 고정 집합이다. 버스는 {@code TransferArrival.Status} 이름과 조회 전
     * 자체 사유, 지하철은 자체 사유만 쓴다. 정류장 ID 처럼 요청마다 달라지는 값은 넣지 않는다.
     *
     * @param mode   {@code bus} · {@code subway}
     * @param reason 못 구한 사유
     */
    public void recordBoardingMiss(String mode, String reason) {
        registry.counter(BOARDING_MISS, "mode", mode, "reason", reason).increment();
    }

    /**
     * 환승 판정 밖 대기(첫 승차·시작 전 환승)의 승차 예측을 물은 수(#480). 이 PR 이 늘리는 실시간 조회량이 바로 이것이다 —
     * 배포 전후 provider 슬롯·호출 지표와 함께 본다.
     *
     * @param position {@code current}(지금 그 대기에 서 있다) · {@code later}(앞 단계에서 굴린 앵커)
     * @param result   {@code known} · {@code unknown} · {@code blocked}
     */
    public void recordWaitAhead(String position, String result) {
        registry.counter(WAIT_AHEAD, "position", position, "result", result).increment();
    }

    private static Duration[] seconds(long... values) {
        Duration[] out = new Duration[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = Duration.ofSeconds(values[i]);
        }
        return out;
    }

    private static Duration[] millis(long... values) {
        Duration[] out = new Duration[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = Duration.ofMillis(values[i]);
        }
        return out;
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
