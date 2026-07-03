package watoo.grd.nextroute.domain.notification.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.LocalDateTime;

/**
 * 경로 출발 알림 발송 outbox. 구독 1건에서 최소 2종·복수 발송이 나가고 각 Toss 재시도가 독립이므로
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

    /** 중복 발송 방지 유니크 키. DEPART=alertId:DEPART, EARLIER=alertId:EARLIER:{seq}. */
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
}
