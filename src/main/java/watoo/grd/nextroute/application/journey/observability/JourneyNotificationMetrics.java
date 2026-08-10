package watoo.grd.nextroute.application.journey.observability;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

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
     * @param kind {@code transient}·{@code permanent}·{@code dead} 중 하나. 재시도 예산이 어디서
     *             타는지 보려면 종류를 갈라야 한다
     */
    public void recordFailed(NotificationChannel channel, String kind) {
        registry.counter(FAILED, "channel", channel.name(), "kind", kind).increment();
    }

    /**
     * @param reason {@code expired}·{@code journey-ended}·{@code route-changed}·{@code superseded}
     */
    public void recordCanceled(String reason) {
        registry.counter(CANCELED, "reason", reason).increment();
    }
}
