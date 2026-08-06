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
     * 버스 <b>실시간</b> 도착 정보를 쓸 수 있는 시야.
     *
     * <p>이 시간 안에 탈 예정이면 실시간을 먼저 보고, 못 잡으면 모델로 내려간다. 그 밖이면
     * <b>실시간을 건너뛰고 모델만</b> 쓴다 — 도착 API 는 지금 접근 중인 다음 한두 대만 알려 주므로
     * 20분 뒤에 탈 차량은 애초에 그 목록에 없다. 답이 없을 호출을 하지 않는다.
     *
     * <p>모델은 시야를 두지 않는다. 운행 중인 차량이 있으면 추정하고, 없으면 모른다고 답한다 —
     * 그 판단은 모델 쪽이 데이터를 보고 하는 것이 맞다.
     */
    private long busRealtimeWindowSeconds = 1200;

    /**
     * 저장된 판정을 믿어 주는 상한.
     *
     * <p>낡음의 1차 기준은 시간이 아니라 <b>가리키는 시각이 지났는가</b>다. 5분 전에 계산했어도
     * "10분 뒤 도착"이면 아직 쓸 만하고, 30초 전에 계산했어도 "이미 도착"이면 쓸모없다.
     *
     * <p>다만 시간 상한도 함께 둔다 — 평가기가 멈춰 있는데 도착 시각만 미래인 값(예: 먼 미래를
     * 가리키는 잘못된 예측)이 계속 최신인 척하는 것을 막는 안전망이다.
     */
    private long staleAfterSeconds = 300;

    /**
     * 가장 짧게 잡을 수 있는 간격.
     *
     * <p>여정 하나가 계속 짧은 간격만 요구하지 못하게 막는 바닥이다. 환승이 코앞이어도 이보다
     * 자주 보지 않는다.
     */
    private long minIntervalSeconds = 10;

    /**
     * 가장 길게 잡을 수 있는 간격.
     *
     * <p><b>{@link #staleAfterSeconds} 보다 작아야 한다.</b> 조회 경로는 저장된 판정이 그 시간보다
     * 오래되면 낡았다고 보고 버린다. 평가 간격이 그보다 길면 평가기가 멀쩡히 도는데도 앱은 계속
     * "모름"을 받는다.
     */
    private long maxIntervalSeconds = 180;

    /** 환승이 멀거나 아예 없을 때의 간격. */
    private long idleIntervalSeconds = 120;

    /** 이 시간 안에 갈아타야 하면 임박으로 본다. */
    private long nearTransferSeconds = 300;

    /** 이 시간보다 멀면 한가한 것으로 본다. */
    private long farTransferSeconds = 900;

    /**
     * 값이 그대로일 때 간격을 늘리는 배수.
     *
     * <p>몇 번을 확인해도 앱에 보일 값이 안 바뀌면 더 물어봐야 얻는 것이 없다는 뜻이다. 다만
     * 임박하거나 위험한 상태에서는 늘리지 않는다 — 급한데 뜸해지면 알림이 늦는다.
     */
    private int unchangedBackoffMultiplier = 2;

    /** 평가 worker 스레드 수. */
    private int poolSize = 4;

    /** worker 대기 큐 길이. 넘치면 그 tick 은 포기하고 다음 tick 에 다시 잡는다. */
    private int queueCapacity = 200;

    @PostConstruct
    void validate() {
        if (tickMs <= 0 || batchSize <= 0 || intervalSeconds <= 0 || leaseSeconds <= 0
                || jitterSeconds <= 0 || batchDeadlineMs <= 0
                || busRealtimeWindowSeconds <= 0 || staleAfterSeconds <= 0
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
        validateAdaptiveInterval();
    }

    /** 간격을 상황에 맞춰 조절할 때 지켜야 하는 관계들. */
    private void validateAdaptiveInterval() {
        if (minIntervalSeconds <= 0 || maxIntervalSeconds <= 0 || idleIntervalSeconds <= 0
                || nearTransferSeconds <= 0 || farTransferSeconds <= 0) {
            throw new IllegalStateException("평가 간격 설정은 모두 0 보다 커야 한다");
        }
        if (unchangedBackoffMultiplier < 1) {
            throw new IllegalStateException("변화 없을 때의 배수는 1 이상이어야 한다");
        }
        if (minIntervalSeconds > intervalSeconds || intervalSeconds > maxIntervalSeconds) {
            throw new IllegalStateException(
                    "간격은 min ≤ 기본 ≤ max 순서여야 한다. 기본값이 범위 밖이면 어떤 상황에서도 "
                            + "쓰이지 않는다");
        }
        if (idleIntervalSeconds > maxIntervalSeconds) {
            throw new IllegalStateException("한가할 때의 간격이 최대 간격을 넘을 수 없다");
        }
        if (nearTransferSeconds >= farTransferSeconds) {
            throw new IllegalStateException("임박 기준은 한가함 기준보다 짧아야 한다");
        }
        if (maxIntervalSeconds >= staleAfterSeconds) {
            throw new IllegalStateException(
                    "최대 평가 간격은 저장된 판정을 믿어 주는 시간보다 짧아야 한다. 길면 평가기가 "
                            + "정상인데도 조회가 그 판정을 낡았다고 버려 앱이 계속 모름을 받는다");
        }
    }

    public Duration interval() {
        return Duration.ofSeconds(intervalSeconds);
    }

    public Duration lease() {
        return Duration.ofSeconds(leaseSeconds);
    }

    public Duration staleAfter() {
        return Duration.ofSeconds(staleAfterSeconds);
    }

    public Duration busRealtimeWindow() {
        return Duration.ofSeconds(busRealtimeWindowSeconds);
    }

    public Duration minInterval() {
        return Duration.ofSeconds(minIntervalSeconds);
    }

    public Duration maxInterval() {
        return Duration.ofSeconds(maxIntervalSeconds);
    }

    public Duration idleInterval() {
        return Duration.ofSeconds(idleIntervalSeconds);
    }

    public Duration nearTransfer() {
        return Duration.ofSeconds(nearTransferSeconds);
    }

    public Duration farTransfer() {
        return Duration.ofSeconds(farTransferSeconds);
    }
}
