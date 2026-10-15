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
    public static final String E2E = "journey.notification.e2e";
    public static final String E2E_UNMEASURED = "journey.notification.e2e.unmeasured";
    public static final String HOLD = "journey.notification.hold";
    public static final String LOST = "journey.notification.lost";
    public static final String SENT_AFTER_EXPIRY = "journey.notification.sent.after-expiry";

    /**
     * 처음부터 끝까지 지연 구간(#446). 감지 SLO(긴급 15초·주의 30초·여유 120초)와 합치기 창 보류(17·32초)를 가를 수 있게
     * 촘촘히 둔다.
     */
    private static final Duration[] E2E_SLO = {
            Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(5),
            Duration.ofMillis(7500), Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(20),
            Duration.ofSeconds(25), Duration.ofSeconds(30), Duration.ofSeconds(45), Duration.ofSeconds(60),
            Duration.ofSeconds(90), Duration.ofSeconds(120), Duration.ofSeconds(180), Duration.ofSeconds(300),
            Duration.ofSeconds(600)};

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

    /**
     * 처음 본 시각 → 발송 접수(#446 알림 SLO "제때"). 합치기 창 보류가 들어 있다 — 보류만 따로 {@link #recordHold} 로 본다.
     *
     * @param tier     처음 본 순간의 등급(URGENT·CAUTION·RELAXED)
     * @param delivery ALERTING·QUIET·none(피로 정책 꺼짐)
     * @param source   feed(변화 피드가 처음 봄) · evaluation(표시 없음 — 평가 시작부터라 하한)
     */
    public void recordE2e(String tier, JourneyCheckpoint checkpoint, String delivery, String source, Duration lag) {
        Timer.builder(E2E)
                .tag("tier", tier).tag("checkpoint", checkpoint.name())
                .tag("delivery", delivery).tag("source", source)
                .serviceLevelObjectives(E2E_SLO)
                .register(registry)
                .record(lag.isNegative() ? Duration.ZERO : lag);
    }

    /** 처음 본 시각이 payload 에 없어 처음부터 끝까지를 못 잰 발송(재탐색·보드·접근 알림, 정책 이전 행). */
    public void recordE2eUnmeasured(JourneyCheckpoint checkpoint) {
        registry.counter(E2E_UNMEASURED, "checkpoint", checkpoint.name()).increment();
    }

    /** 합치기 창 보류 = 발송 예정 − 적재(의도된 지연). 처음부터 끝까지 지연에서 이만큼은 설계다. */
    public void recordHold(JourneyCheckpoint checkpoint, String delivery, Duration hold) {
        Timer.builder(HOLD)
                .tag("checkpoint", checkpoint.name()).tag("delivery", delivery)
                .serviceLevelObjectives(E2E_SLO)
                .register(registry)
                .record(hold.isNegative() ? Duration.ZERO : hold);
    }

    /**
     * 보냈어야 했는데 못 보낸 행(#446 알림 SLO "빠짐없이"). 만료·재시도 전 만료·DEAD·영구 실패. 대체·회복·여정 종료·경로
     * 변경·lease 만료 취소는 정상이라 여기 오지 않는다. SLO 는 울림(ALERTING·none) 위험·재탐색만 센다 — 접근·조용한 갱신도
     * tag 로 남겨 둔다.
     *
     * @param reason {@code expired}·{@code expired-before-retry}·{@code dead}·{@code failed:<코드>}
     */
    public void recordLost(JourneyCheckpoint checkpoint, String delivery, String reason) {
        registry.counter(LOST, "checkpoint", checkpoint.name(), "delivery", delivery, "reason", reason).increment();
    }

    /** 만료 시각을 넘겨 접수된 발송(#446 "낡지 않게"). 발송 직전 재확인 뒤 공급자 왕복이 길면 생길 수 있다. 0 이어야 한다. */
    public void recordSentAfterExpiry(JourneyCheckpoint checkpoint) {
        registry.counter(SENT_AFTER_EXPIRY, "checkpoint", checkpoint.name()).increment();
    }
}
