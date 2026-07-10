package watoo.grd.nextroute.domain.notification.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.LocalDateTime;

/**
 * 버스 도착 알림 발송 outbox. 구독({@link BusArrivalAlert})에서 발송 라이프사이클을 분리한다.
 * polling 은 여기 enqueue 만 하고 Toss 는 relay 가 호출(send-after-commit). 채널별 1 row.
 */
@Entity
@Table(name = "bus_alert_notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BusAlertNotification extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alert_id", nullable = false)
    private BusArrivalAlert alert;

    /** 중복 발송 방지. 'BUS_ARRIVAL:{alertId}:{eventType}:{channel}'. */
    @Column(nullable = false, unique = true)
    private String dedupKey;

    /** 발송 채널: TOSS | FCM | SMS ... */
    @Column(nullable = false)
    private String channel;

    /** 이벤트 유형: ARRIVAL ... */
    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private BusNotificationStatus status;

    @Column(nullable = false)
    private LocalDateTime scheduledAt;

    /** 발송 스냅샷(JSON). enqueue 시점 값(one-shot: 발송 지연돼도 재계산 안 함). */
    @Column(length = 1000)
    private String payload;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private int requeueCount;

    private LocalDateTime lastRequeuedAt;

    private LocalDateTime processingStartedAt;

    private LocalDateTime sentAt;

    @Column(length = 500)
    private String lastFailureReason;

    @Builder
    public BusAlertNotification(BusArrivalAlert alert, String dedupKey, String channel, String eventType,
                                LocalDateTime scheduledAt, String payload) {
        this.alert = alert;
        this.dedupKey = dedupKey;
        this.channel = channel;
        this.eventType = eventType;
        this.scheduledAt = scheduledAt;
        this.payload = payload;
        this.status = BusNotificationStatus.PENDING;
        this.attemptCount = 0;
    }

    public void markCanceled() {
        this.status = BusNotificationStatus.CANCELED;
    }

    public void markSent(LocalDateTime now) {
        this.status = BusNotificationStatus.SENT;
        this.sentAt = now;
    }

    public void markFailed(String reason) {
        this.status = BusNotificationStatus.FAILED;
        this.lastFailureReason = reason;
    }

    public void markDead(String reason) {
        this.status = BusNotificationStatus.DEAD;
        this.lastFailureReason = reason;
    }

    /** transient 실패 → 재시도 위해 PENDING 복귀. scheduledAt 을 백오프 시각으로 미룬다. */
    public void releaseToPending(String reason, LocalDateTime nextAttemptAt) {
        this.status = BusNotificationStatus.PENDING;
        this.lastFailureReason = reason;
        this.scheduledAt = nextAttemptAt;
    }
}
