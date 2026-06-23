package watoo.grd.nextroute.domain.notification.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;
import watoo.grd.nextroute.domain.user.entity.User;

import java.time.LocalDateTime;

/**
 * 버스 도착 알림 구독(one-shot). 스케줄러가 도착을 폴링해 곧 도착 시 토스 푸시 1회 발송.
 * 상태 전이: PENDING → (claim) PROCESSING → SENT/FAILED, 또는 PENDING/PROCESSING → EXPIRED/CANCELED.
 */
@Entity
@Table(name = "bus_arrival_alert")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BusArrivalAlert extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String stopId;

    @Column(nullable = false)
    private String routeId;

    @Column(nullable = false)
    private Integer ord;

    @Column(nullable = false)
    private String routeName;   // 서버 보강값

    @Column(nullable = false)
    private String stopName;    // 서버 보강값

    @Column(nullable = false)
    private LocalDateTime userEta;   // 사용자 정류소 예상 도착 시각. 이 시각 이후 오는 버스를 타겟으로 발송.

    @Column(nullable = false)
    private Integer busArrivalMinutes;   // 등록 시점 버스 예상 도착(분). GET 응답 표시용.

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AlertStatus status;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime sentAt;

    private LocalDateTime processingStartedAt;

    private LocalDateTime lastAttemptAt;

    @Column(nullable = false)
    private int attemptCount;

    @Column(length = 500)
    private String lastFailureReason;

    @Builder
    public BusArrivalAlert(User user, String stopId, String routeId, Integer ord,
                           String routeName, String stopName, LocalDateTime userEta,
                           Integer busArrivalMinutes, LocalDateTime expiresAt) {
        this.user = user;
        this.stopId = stopId;
        this.routeId = routeId;
        this.ord = ord;
        this.routeName = routeName;
        this.stopName = stopName;
        this.userEta = userEta;
        this.busArrivalMinutes = busArrivalMinutes;
        this.expiresAt = expiresAt;
        this.status = AlertStatus.PENDING;
        this.attemptCount = 0;
    }

    /** PENDING → PROCESSING (claim). 발송 시도 직전 호출. */
    public void markProcessing(LocalDateTime now) {
        this.status = AlertStatus.PROCESSING;
        this.processingStartedAt = now;
        this.lastAttemptAt = now;
        this.attemptCount++;
    }

    public void markSent(LocalDateTime now) {
        this.status = AlertStatus.SENT;
        this.sentAt = now;
    }

    public void markFailed(String reason) {
        this.status = AlertStatus.FAILED;
        this.lastFailureReason = reason;
    }

    public void markExpired() {
        this.status = AlertStatus.EXPIRED;
    }

    public void markCanceled() {
        this.status = AlertStatus.CANCELED;
    }

    /** transient 실패 → 재시도 위해 PENDING 복귀. */
    public void releaseToPending(String reason) {
        this.status = AlertStatus.PENDING;
        this.lastFailureReason = reason;
    }
}
