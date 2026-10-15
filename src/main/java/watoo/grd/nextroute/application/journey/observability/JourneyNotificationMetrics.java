package watoo.grd.nextroute.application.journey.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.time.Duration;

/**
 * 여정 알림 outbox 관측 지표.
 *
 * <p>버스 outbox 는 Micrometer 계측이 없지만 여기는 최소한을 넣는다 — 실제 발송이 붙는 PR3
 * 전까지 이 경로가 혼자 운영되므로, 쌓이는데 안 나가는 상태를 지표 없이 알 방법이 없다.
 *
 * <p><b>여정 번호·사용자 번호·token 은 tag 로 쓰지 않는다.</b> 시계열이 무한히 늘어나고
 * 개인정보가 지표 저장소로 샌다.
 */
@Component
@RequiredArgsConstructor
public class JourneyNotificationMetrics {

    public static final String ENQUEUED = "journey.notification.enqueued";
    public static final String CLAIMED = "journey.notification.claimed";
    public static final String SENT = "journey.notification.sent";
    public static final String FAILED = "journey.notification.failed";
    public static final String CANCELED = "journey.notification.canceled";
    public static final String TOKEN_DEACTIVATED = "journey.notification.push.token.deactivated";
    public static final String NO_ACTIVE_TOKEN = "journey.notification.push.no.active.token";
    public static final String DELIVERY_LAG = "journey.notification.delivery-lag";
    public static final String ENQUEUED_DELIVERY = "journey.notification.enqueued.delivery";
    public static final String ALERT_CLAIM = "journey.notification.alert.claim";

    /** 적재→발송 지연 구간. 감지 SLO 와 같은 단위로 읽도록 초 단위 경계를 둔다. */
    private static final Duration[] DELIVERY_SLO = {
            Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2),
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30),
            Duration.ofSeconds(60)};

    private final MeterRegistry registry;

    /** 실제로 행이 생겼을 때만 센다. 중복이라 0행인 것은 "이미 알렸다"라 사건이 아니다. */
    public void recordEnqueued(JourneyCheckpoint checkpoint) {
        registry.counter(ENQUEUED, "checkpoint", checkpoint.name()).increment();
    }

    /** relay 가 집어 간 수. enqueue 와 벌어지면 relay 가 멈춘 것이다. */
    public void recordClaimed(int count) {
        if (count > 0) {
            registry.counter(CLAIMED).increment(count);
        }
    }

    public void recordSent(NotificationChannel channel) {
        registry.counter(SENT, "channel", channel.name()).increment();
    }

    /**
     * 적재부터 발송 성공까지. 재시도로 늦게 나간 건도 첫 적재 시각부터 잰다 — 사용자에게는 그
     * 시간 전부가 늦은 것이다.
     *
     * <p>위험 판정이 알림으로 사용자에게 닿는 서버 구간의 마지막 몫이다. 평가기 지연과 더하면
     * "데이터 변화 → 알림 발송" 전체가 된다.
     */
    public void recordDeliveryLag(NotificationChannel channel, Duration lag) {
        Timer.builder(DELIVERY_LAG).tag("channel", channel.name())
                .serviceLevelObjectives(DELIVERY_SLO)
                .register(registry)
                .record(lag.isNegative() ? Duration.ZERO : lag);
    }

    /**
     * @param kind {@code transient}·{@code permanent}·{@code dead} 중 하나. 재시도 예산이 어디서
     *             타는지 보려면 종류를 갈라야 한다
     */
    public void recordFailed(NotificationChannel channel, String kind) {
        registry.counter(FAILED, "channel", channel.name(), "kind", kind).increment();
    }

    /**
     * @param reason {@code expired}·{@code journey-ended}·{@code route-changed}·{@code superseded}·
     *               {@code lease-expired}·{@code command-changed}·{@code transfer-missed}·
     *               {@code already-boarded}. 사유마다 이름을 따로 두는 이유는 "왜 안 갔나"를
     *               운영에서 갈라 봐야 하기 때문이다 — 뭉뚱그리면 재검증이 과한지 모자란지 모른다
     */
    public void recordCanceled(String reason) {
        registry.counter(CANCELED, "reason", reason).increment();
    }

    /**
     * provider 가 죽었다고 답해 우리가 끈 token 수.
     *
     * <p>정상적인 기기 교체로도 꾸준히 오른다. 갑자기 치솟으면 자격증명·payload 사고를 의심한다.
     */
    public void recordTokenDeactivated(int count) {
        if (count > 0) {
            registry.counter(TOKEN_DEACTIVATED).increment(count);
        }
    }

    /**
     * 보낼 곳이 하나도 없어 끝난 행.
     *
     * <p>provider 실패와 섞으면 안 된다. 이것은 발송 경로의 고장이 아니라 "그 사용자는 알림을
     * 받을 기기가 없다"는 사실이라, 섞이면 정상 token churn 이 실패 지표를 부풀린다.
     */
    public void recordNoActiveToken(NotificationChannel channel) {
        registry.counter(NO_ACTIVE_TOKEN, "channel", channel.name()).increment();
    }

    /**
     * 피로 정책(#428)이 켜졌을 때 실제로 생긴 행을 발송 종류로 다시 센다.
     *
     * <p>{@link #ENQUEUED} 에 tag 를 더하지 않고 이름을 나눈 이유: 같은 이름에 tag 집합이 다른 계측이 섞이면
     * Prometheus 등록이 깨진다. 플래그가 꺼져 있으면 이 카운터는 아예 생기지 않는다.
     */
    public void recordEnqueuedDelivery(JourneyCheckpoint checkpoint, String delivery) {
        registry.counter(ENQUEUED_DELIVERY, "checkpoint", checkpoint.name(), "delivery", delivery).increment();
    }

    /**
     * 울릴 권리 판정 결과(#428 PR-1b).
     *
     * @param outcome {@code won}(처음 땀)·{@code inherited}(대체한 알림이 물려받음)·{@code quiet}(이미 누가 땀)·
     *                {@code quiet-dominated}(재탐색이 이미 울려 조용한 위험 알림을 안 보냄)·{@code released}(울릴
     *                알림이 실제로 안 나가 돌려받음). "경로 버전당 울림 2번"이 생기면 won+inherited 와 released 의
     *                차이부터 본다
     */
    public void recordAlertClaim(String outcome) {
        registry.counter(ALERT_CLAIM, "outcome", outcome).increment();
    }
}
