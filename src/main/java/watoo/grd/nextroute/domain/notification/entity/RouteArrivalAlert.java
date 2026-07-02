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
 * 목표 도착시간 기반 출발 알림 구독(계산 상태만). 발송/재시도 상태는 {@link RouteArrivalAlertNotification}(outbox)이 소유.
 * 배경에서 소요시간을 재추정해 leaveByAt(언제 출발) 을 갱신하고, 필요한 알림 row 를 생성한다.
 * 상태 전이는 시간(targetArrivalAt/expiresAt) 또는 사용자 취소만이 트리거하며 발송 성패와 무관하다.
 */
@Entity
@Table(name = "route_arrival_alert")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteArrivalAlert extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Double startX;
    @Column(nullable = false)
    private Double startY;
    private String startName;

    @Column(nullable = false)
    private Double endX;
    @Column(nullable = false)
    private Double endY;
    private String endName;

    /** 목표 도착시각. */
    @Column(nullable = false)
    private LocalDateTime targetArrivalAt;

    /** 출발 리마인드 리드타임(5분 단위, 고정). */
    @Column(nullable = false)
    private Integer userLeadMinutes;

    /** 안전 버퍼(분). leaveByAt 계산에 반영. */
    @Column(nullable = false)
    private Integer bufferMinutes;

    /** 최근 재추정 소요시간(초). */
    private Integer lastTotalDurationSec;

    /** 언제 출발해야 목표 도착 가능한가 (= targetArrivalAt − lastTotalDurationSec − bufferMinutes). */
    private LocalDateTime leaveByAt;

    /** 직전 재탐색 시점 leaveByAt (앞당김 감지 비교용). */
    private LocalDateTime prevLeaveByAt;

    private LocalDateTime lastResearchAt;

    /** 적응형 cadence 로 계산된 다음 재탐색 예정시각(§6). */
    private LocalDateTime nextResearchAt;

    /** 재탐색 성공 호출 횟수(비용 상한 추적). */
    @Column(nullable = false)
    private int researchCount;

    /** EARLIER_DEPARTURE 발행 카운터(0부터 단조증가). dedup·cap 근거. */
    @Column(nullable = false)
    private int earlierSeq;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private RouteAlertStatus status;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Builder
    public RouteArrivalAlert(User user, Double startX, Double startY, String startName,
                             Double endX, Double endY, String endName,
                             LocalDateTime targetArrivalAt, Integer userLeadMinutes, Integer bufferMinutes,
                             Integer lastTotalDurationSec, LocalDateTime leaveByAt,
                             LocalDateTime lastResearchAt, LocalDateTime nextResearchAt,
                             LocalDateTime expiresAt) {
        this.user = user;
        this.startX = startX;
        this.startY = startY;
        this.startName = startName;
        this.endX = endX;
        this.endY = endY;
        this.endName = endName;
        this.targetArrivalAt = targetArrivalAt;
        this.userLeadMinutes = userLeadMinutes;
        this.bufferMinutes = bufferMinutes;
        this.lastTotalDurationSec = lastTotalDurationSec;
        this.leaveByAt = leaveByAt;
        this.prevLeaveByAt = leaveByAt;
        this.lastResearchAt = lastResearchAt;
        this.nextResearchAt = nextResearchAt;
        this.researchCount = lastTotalDurationSec != null ? 1 : 0;
        this.earlierSeq = 0;
        this.status = RouteAlertStatus.ACTIVE;
        this.expiresAt = expiresAt;
    }

    public boolean isActive() {
        return status == RouteAlertStatus.ACTIVE;
    }

    public void markCanceled() {
        this.status = RouteAlertStatus.CANCELED;
    }

    public void markExpired() {
        this.status = RouteAlertStatus.EXPIRED;
    }

    public void markDone() {
        this.status = RouteAlertStatus.DONE;
    }
}
