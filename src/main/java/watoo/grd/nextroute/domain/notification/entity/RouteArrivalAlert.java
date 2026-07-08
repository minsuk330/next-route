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
 * 목표 도착시간 기반 출발 알림 구독 v2(계산/상태만). 발송/재시도 상태는 {@link RouteArrivalAlertNotification}(outbox)이 소유.
 * 흐름: baseline(등록·STATIC) → first check(REALTIME) → DEPARTURE 발송 → final check(타깃 재조회) → optional CHANGE → DONE.
 * checker/dispatcher 가 별도 스케줄러로 동시 접근하므로 {@link Version} 낙관락으로 정합을 지킨다.
 */
@Entity
@Table(name = "route_arrival_alert")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteArrivalAlert extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** occurrence 출처 schedule id(null=구모델/직접 생성). 상태머신엔 무관, 재무장·중복방지 근거. */
    private Long scheduleId;

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

    /** 출발 리마인드 리드타임(분, {0,5,10,15}). */
    @Column(nullable = false)
    private Integer userLeadMinutes;

    /** 안전 버퍼(분). leaveBy 계산에 반영. */
    @Column(nullable = false)
    private Integer bufferMinutes;

    // ── baseline (등록 시 STATIC evaluator) ──────────────────────────────
    /** 등록 시 정적 추정 소요(초). */
    private Integer baselineDurationSec;
    /** 등록 시 추정 출발시각(= target − baselineDurationSec − buffer). */
    private LocalDateTime baselineLeaveByAt;
    /** first check 예정시각(= baselineLeaveBy − userLead − safetyMargin). */
    private LocalDateTime firstCheckAt;

    // ── first check (REALTIME evaluator) ─────────────────────────────────
    private Integer recommendedDurationSec;
    /** 확정 추천 출발시각(= target − recommendedDurationSec − buffer). */
    private LocalDateTime recommendedLeaveByAt;
    /** DEPARTURE 발송 예정시각(= recommendedLeaveBy − userLead). */
    private LocalDateTime departureNotificationAt;

    // ── 발송 기록 (dispatcher) ────────────────────────────────────────────
    /** 마지막으로 발송한 leaveBy(재검증 변경 감지 비교 기준). */
    private LocalDateTime lastNotifiedLeaveByAt;
    private LocalDateTime lastNotifiedAt;

    /** 선택 경로 스냅샷 JSON(first check 기록, final check 타깃 재조회용). */
    @Column(length = 2000)
    private String selectedPathSnapshot;

    // ── final check ───────────────────────────────────────────────────────
    private LocalDateTime finalCheckedAt;

    @Enumerated(EnumType.STRING)
    private RouteAlertReliability reliability;

    /** 변경 알림 발행 카운터(0부터 단조증가). cap·dedup 근거. */
    @Column(nullable = false)
    private int changeAlertCount;

    // ── 상태머신 ────────────────────────────────────────────────────────────
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private RouteAlertPhase phase;

    /** 다음 체크 액션 예정시각. WAITING_DEPARTURE 는 NULL(dispatcher 소유). */
    private LocalDateTime nextActionAt;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private RouteAlertStatus status;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Builder
    public RouteArrivalAlert(User user, Long scheduleId, Double startX, Double startY, String startName,
                             Double endX, Double endY, String endName,
                             LocalDateTime targetArrivalAt, Integer userLeadMinutes, Integer bufferMinutes,
                             Integer baselineDurationSec, LocalDateTime baselineLeaveByAt,
                             LocalDateTime firstCheckAt, RouteAlertReliability reliability,
                             LocalDateTime expiresAt) {
        this.user = user;
        this.scheduleId = scheduleId;
        this.startX = startX;
        this.startY = startY;
        this.startName = startName;
        this.endX = endX;
        this.endY = endY;
        this.endName = endName;
        this.targetArrivalAt = targetArrivalAt;
        this.userLeadMinutes = userLeadMinutes;
        this.bufferMinutes = bufferMinutes;
        this.baselineDurationSec = baselineDurationSec;
        this.baselineLeaveByAt = baselineLeaveByAt;
        this.firstCheckAt = firstCheckAt;
        this.reliability = reliability;
        this.changeAlertCount = 0;
        this.phase = RouteAlertPhase.WAITING_FIRST_CHECK;
        this.nextActionAt = firstCheckAt;
        this.status = RouteAlertStatus.ACTIVE;
        this.expiresAt = expiresAt;
    }

    public boolean isActive() {
        return status == RouteAlertStatus.ACTIVE;
    }

    /** first check 완료: 추천값·스냅샷 확정 후 발송 대기로. nextActionAt=NULL(dispatcher 소유). */
    public void applyFirstCheck(Integer recommendedDurationSec, LocalDateTime recommendedLeaveByAt,
                                LocalDateTime departureNotificationAt, RouteAlertReliability reliability,
                                String selectedPathSnapshot) {
        this.recommendedDurationSec = recommendedDurationSec;
        this.recommendedLeaveByAt = recommendedLeaveByAt;
        this.departureNotificationAt = departureNotificationAt;
        this.reliability = reliability;
        this.selectedPathSnapshot = selectedPathSnapshot;
        this.phase = RouteAlertPhase.WAITING_DEPARTURE;
        this.nextActionAt = null;
    }

    /**
     * DEPARTURE 발송 성공 기록(dispatcher, 발송과 동일 트랜잭션).
     * finalCheckAt null → 종결(DONE), 아니면 final check 대기.
     */
    public void recordNotified(LocalDateTime leaveByAt, LocalDateTime at, LocalDateTime finalCheckAt) {
        this.lastNotifiedLeaveByAt = leaveByAt;
        this.lastNotifiedAt = at;
        if (finalCheckAt == null) {
            this.phase = RouteAlertPhase.COMPLETED;
            this.status = RouteAlertStatus.DONE;
            this.nextActionAt = null;
        } else {
            this.phase = RouteAlertPhase.WAITING_FINAL_CHECK;
            this.nextActionAt = finalCheckAt;
        }
    }

    /** final check 완료: 종결(DONE). */
    public void applyFinalCheck(LocalDateTime at, RouteAlertReliability reliability) {
        this.finalCheckedAt = at;
        this.reliability = reliability;
        this.phase = RouteAlertPhase.COMPLETED;
        this.status = RouteAlertStatus.DONE;
        this.nextActionAt = null;
    }

    public void incrementChangeAlertCount() {
        this.changeAlertCount++;
    }

    public void markCanceled() {
        this.status = RouteAlertStatus.CANCELED;
        this.nextActionAt = null;
    }

    /** 스케줄 수정으로 대체(발송 전): SUPERSEDED + soft-delete. 유니크 슬롯 해제·종결판정 제외. */
    public void markSuperseded() {
        this.status = RouteAlertStatus.SUPERSEDED;
        this.phase = RouteAlertPhase.COMPLETED;
        this.nextActionAt = null;
        markDeleted();
    }

    /** 스케줄 수정으로 조기종결(일회성 발송 후): DONE 유지(발송 사실 보존) + soft-delete(유니크 슬롯 해제). */
    public void markDoneOnEdit() {
        this.status = RouteAlertStatus.DONE;
        this.phase = RouteAlertPhase.COMPLETED;
        this.nextActionAt = null;
        markDeleted();
    }

    public void markExpired() {
        this.status = RouteAlertStatus.EXPIRED;
        this.nextActionAt = null;
    }
}
