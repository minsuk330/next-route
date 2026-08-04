package watoo.grd.nextroute.application.journey.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 주기 평가기의 운영값.
 *
 * <p>기본값은 <b>꺼짐</b>이다. 평가기는 외부 API 를 상시로 부르므로, 배포와 동시에 도는 것이
 * 아니라 운영이 고른 시점에 켜야 한다. Phase 8 의 점진 배포가 이 스위치를 쓴다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "journey.navigation.evaluator")
public class JourneyEvaluatorProperties {

    private boolean enabled = false;

    /** claim 주기. 이 간격마다 "지금 평가할 여정"을 긁는다. */
    private long tickMs = 5_000;

    /** 한 번에 claim 하는 여정 수. */
    private int batchSize = 100;

    /**
     * 다음 평가까지의 간격.
     *
     * <p>고정값이다. 환승이 임박했을 때 좁히는 일은 별도 작업(⑥)의 몫이다 — 둘을 함께 넣으면
     * 부하 측정의 기준이 흔들린다.
     */
    private long intervalSeconds = 30;

    /**
     * claim 선점 시간.
     *
     * <p>claim 은 {@code next_evaluation_at} 을 이만큼 미래로 밀어 둔다. 평가가 끝나면 실제 다음
     * 시각으로 덮어쓰고, 워커가 죽으면 이 시간이 지난 뒤 다른 워커가 다시 잡는다.
     *
     * <p><b>평가 간격보다 길어야 한다.</b> 짧으면 평가가 끝나기 전에 다른 워커가 같은 여정을 잡아,
     * 둘이 번갈아 서로의 결과를 버리며 아무도 저장하지 못하는 상태가 된다.
     */
    private long leaseSeconds = 60;

    /**
     * 여정별로 시각을 흩뜨리는 폭.
     *
     * <p>두 가지 일을 한다. 첫째, claim 이 심는 lease 시각이 여정마다 달라져 그 값 자체가 소유권을
     * 가르는 증서가 된다. 둘째, 다음 평가와 실패 재시도가 한 tick 에 몰리지 않는다.
     */
    private long jitterSeconds = 7;

    /**
     * 한 배치를 처리하는 상한.
     *
     * <p>lease 보다 훨씬 짧아야 한다. 한 여정의 평가가 lease 를 넘기면 그 워커는 소유권을 잃어
     * 결과를 버리게 되고, 그러면 평가가 겉으로만 돌고 아무것도 저장되지 않는다.
     */
    private long batchDeadlineMs = 4_000;

    /**
     * 이 시간보다 먼 <b>버스</b> 환승은 조회하지 않는다.
     *
     * <p>실시간 도착 정보는 다음 한두 대만 알려 주고, 모델은 지금 운행 중인 차량의 위치로만
     * 추정한다. 20분 뒤에 올 버스는 아직 노선 앞쪽에 없어서 어느 쪽도 답을 못 준다. 그때 부르면
     * "차량 없음"을 받으려고 외부 API 를 쓰는 셈이다.
     */
    private long transferRealtimeHorizonSeconds = 900;

    /**
     * 이 시간보다 먼 <b>지하철</b> 환승은 조회하지 않는다.
     *
     * <p>지하철 실시간은 "지금 역으로 들어오는 열차"만 말한다. 버스보다 시야가 짧다. 지하철은
     * 모델도 없으므로(SC-6) 그 밖은 모른다고 답한다.
     */
    private long subwayRealtimeHorizonSeconds = 480;

    /** 평가 worker 스레드 수. */
    private int poolSize = 4;

    /** worker 대기 큐 길이. 넘치면 그 tick 은 포기하고 다음 tick 에 다시 잡는다. */
    private int queueCapacity = 200;

    @PostConstruct
    void validate() {
        if (tickMs <= 0 || batchSize <= 0 || intervalSeconds <= 0 || leaseSeconds <= 0
                || jitterSeconds <= 0 || batchDeadlineMs <= 0
                || transferRealtimeHorizonSeconds <= 0 || subwayRealtimeHorizonSeconds <= 0
                || poolSize <= 0 || queueCapacity <= 0) {
            throw new IllegalStateException("journey.navigation.evaluator 설정은 모두 0 보다 커야 한다");
        }
        if (leaseSeconds <= intervalSeconds) {
            throw new IllegalStateException(
                    "평가 lease 는 평가 간격보다 길어야 한다. 짧으면 평가가 끝나기 전에 다른 워커가 "
                            + "같은 여정을 잡아 아무도 결과를 저장하지 못한다");
        }
        if (batchDeadlineMs >= leaseSeconds * 1_000) {
            throw new IllegalStateException(
                    "배치 처리 상한은 평가 lease 보다 짧아야 한다. 넘기면 매번 소유권을 잃는다");
        }
        if (jitterSeconds >= intervalSeconds) {
            throw new IllegalStateException("흩뜨리는 폭은 평가 간격보다 작아야 한다");
        }
    }

    public Duration interval() {
        return Duration.ofSeconds(intervalSeconds);
    }

    public Duration lease() {
        return Duration.ofSeconds(leaseSeconds);
    }

    public Duration transferRealtimeHorizon() {
        return Duration.ofSeconds(transferRealtimeHorizonSeconds);
    }

    public Duration subwayRealtimeHorizon() {
        return Duration.ofSeconds(subwayRealtimeHorizonSeconds);
    }
}
