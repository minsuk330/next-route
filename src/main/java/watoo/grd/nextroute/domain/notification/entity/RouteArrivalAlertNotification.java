package watoo.grd.nextroute.domain.notification.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.LocalDateTime;

/**
 * 경로 출발 알림 발송 outbox. 구독 1건에서 복수 발송이 나가고 각 Toss 재시도가 독립이므로
 * 구독 상태({@link RouteArrivalAlert})와 분리한다. claim/reclaim/재시도는 이 row 단위로 처리.
 */
@Entity
@Table(name = "route_arrival_alert_notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteArrivalAlertNotification extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alert_id", nullable = false)
    private RouteArrivalAlert alert;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private RouteNotificationType type;

    /** 중복 발송 방지 유니크 키. DEPARTURE=alertId:DEPARTURE, CHANGE=alertId:CHANGE. */
    @Column(nullable = false, unique = true)
    private String dedupKey;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private RouteNotificationStatus status;

    /** 발송 예정 시각. */
    @Column(nullable = false)
    private LocalDateTime scheduledAt;

    /** 이 row 가 근거한 leaveByAt (발송 시 stale 검증용). */
    @Column(nullable = false)
    private LocalDateTime targetLeaveByAt;

    /** 발송 스냅샷(JSON). 발송 순간 departInMin 은 재계산하므로 참고용. */
    @Column(length = 1000)
    private String payload;

    @Column(nullable = false)
    private int attemptCount;

    /** admin requeue 누적 횟수. 무제한 재시도 방지 상한(maxRequeue)과 대조. */
    @Column(nullable = false)
    private int requeueCount;

    private LocalDateTime lastRequeuedAt;

    private LocalDateTime processingStartedAt;

    private LocalDateTime sentAt;

    @Column(length = 500)
    private String lastFailureReason;

    @Builder
    public RouteArrivalAlertNotification(RouteArrivalAlert alert, RouteNotificationType type,
                                         String dedupKey, LocalDateTime scheduledAt,
                                         LocalDateTime targetLeaveByAt, String payload) {
        this.alert = alert;
        this.type = type;
        this.dedupKey = dedupKey;
        this.scheduledAt = scheduledAt;
        this.targetLeaveByAt = targetLeaveByAt;
        this.payload = payload;
        this.status = RouteNotificationStatus.PENDING;
        this.attemptCount = 0;
    }

    public void markCanceled() {
        this.status = RouteNotificationStatus.CANCELED;
    }

    /** PENDING → PROCESSING(claim). attemptCount 증가. */
    public void markProcessing(LocalDateTime now) {
        this.status = RouteNotificationStatus.PROCESSING;
        this.processingStartedAt = now;
        this.attemptCount++;
    }

    public void markSent(LocalDateTime now) {
        this.status = RouteNotificationStatus.SENT;
        this.sentAt = now;
    }

    public void markFailed(String reason) {
        this.status = RouteNotificationStatus.FAILED;
        this.lastFailureReason = reason;
    }

    /** transient 재시도 소진 → DEAD(수동 개입 대기). permanent(FAILED)와 구분. */
    public void markDead(String reason) {
        this.status = RouteNotificationStatus.DEAD;
        this.lastFailureReason = reason;
    }

    /**
     * DEAD → PENDING 재투입(admin). 기본은 attemptCount 를 1 줄여(다음 claim 이 +1) 정확히 1회만 더 시도.
     * resetAttempts=true 면 재시도 예산 전체 재부여. requeueCount 증가로 반복 requeue 상한을 건다.
     */
    public void requeue(LocalDateTime now, boolean resetAttempts) {
        this.status = RouteNotificationStatus.PENDING;
        this.scheduledAt = now;
        this.attemptCount = resetAttempts ? 0 : Math.max(0, this.attemptCount - 1);
        this.lastFailureReason = null;
        this.requeueCount++;
        this.lastRequeuedAt = now;
    }

    /** transient 실패 → 재시도 위해 PENDING 복귀. scheduledAt 을 다음 시도시각으로 미룬다(백오프). */
    public void releaseToPending(String reason, LocalDateTime nextAttemptAt) {
        this.status = RouteNotificationStatus.PENDING;
        this.lastFailureReason = reason;
        this.scheduledAt = nextAttemptAt;
    }

    /** reclaim(고착 PROCESSING → PENDING). */
    public void releaseForReclaim(LocalDateTime now) {
        this.status = RouteNotificationStatus.PENDING;
        this.scheduledAt = now;
    }
}
