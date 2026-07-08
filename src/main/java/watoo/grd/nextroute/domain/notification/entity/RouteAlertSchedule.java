package watoo.grd.nextroute.domain.notification.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;
import watoo.grd.nextroute.domain.user.entity.User;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 반복(요일)/일회성 목표 도착 알림 구독 규칙. 좌표·시각·요일셋을 소유하며, {@code RouteAlertScheduleSpawnService}
 * 가 이 규칙으로 in-flight occurrence({@link RouteArrivalAlert}) 를 최대 1개 유지한다.
 * occurrence 의 phase 상태머신은 여전히 {@link RouteArrivalAlert} 소유 — 여기선 구독 생애만 관리한다.
 * <p>materialize 실패는 terminal 이 아니다: too-late/운행불가는 roll-forward(상태변경 없음),
 * 시스템오류는 retry metadata 기록 후 백오프 재시도. spawner 동시성은 {@link Version} 낙관락으로 방어한다.
 */
@Entity
@Table(name = "route_alert_schedule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteAlertSchedule extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 좌표 컬럼: 끝자리 단일 대문자(startX/endX)는 CamelCaseToUnderscores 가 underscore 를 붙이지 않아
    // startx/starty/endx/endy 로 매핑된다(V1 baseline·route_arrival_alert 와 동일 규칙).
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

    /** 목표 도착 시각(HH:mm). 날짜는 nextOccurrence 가 결정한다. */
    @Column(nullable = false)
    private LocalTime arrivalTime;

    /** 반복 요일 bitmask(bit0=월…bit6=일). 0 = 일회성(요일 무관). */
    @Column(nullable = false)
    private int weekdaysMask;

    /** true=반복(매주 선택 요일), false=일회성(다음 도래 1회, 발송 성공까지 이월). */
    @Column(name = "repeat", nullable = false)
    private boolean repeat;

    @Column(nullable = false)
    private Integer userLeadMinutes;

    @Column(nullable = false)
    private Integer bufferMinutes;

    /** 마지막으로 materialize 된 occurrence 의 목표시각(중복/상관 근거). null=아직 생성 전. */
    private LocalDateTime lastSpawnedTargetArrivalAt;

    // ── retry metadata (시스템오류만. too-late/운행불가=roll-forward 는 미기록) ──────────────
    private LocalDateTime lastMaterializationFailedAt;
    @Column(length = 500)
    private String lastMaterializationError;
    /** 연속 시스템오류 횟수(성공 시 0 리셋). 지수 백오프 지수. */
    @Column(nullable = false)
    private int materializationRetryCount;
    /** 백오프 게이트: now < 이 값이면 spawn skip. */
    private LocalDateTime nextMaterializationRetryAt;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private RouteAlertScheduleStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @Builder
    public RouteAlertSchedule(User user, Double startX, Double startY, String startName,
                              Double endX, Double endY, String endName,
                              LocalTime arrivalTime, int weekdaysMask, boolean repeat,
                              Integer userLeadMinutes, Integer bufferMinutes) {
        this.user = user;
        this.startX = startX;
        this.startY = startY;
        this.startName = startName;
        this.endX = endX;
        this.endY = endY;
        this.endName = endName;
        this.arrivalTime = arrivalTime;
        this.weekdaysMask = weekdaysMask;
        this.repeat = repeat;
        this.userLeadMinutes = userLeadMinutes;
        this.bufferMinutes = bufferMinutes;
        this.status = RouteAlertScheduleStatus.ACTIVE;
    }

    public boolean isActive() {
        return status == RouteAlertScheduleStatus.ACTIVE;
    }

    /**
     * 구독 규칙 수정(좌표 제외). 필드 갱신 + retry 백오프 메타 리셋.
     * ★리셋 필수: 남아있으면 수정 후 즉시 재 materialize 가 백오프 게이트({@code nextMaterializationRetryAt})에 막혀 skip 된다.
     */
    public void update(LocalTime arrivalTime, int weekdaysMask, boolean repeat,
                       Integer userLeadMinutes, Integer bufferMinutes) {
        this.arrivalTime = arrivalTime;
        this.weekdaysMask = weekdaysMask;
        this.repeat = repeat;
        this.userLeadMinutes = userLeadMinutes;
        this.bufferMinutes = bufferMinutes;
        this.materializationRetryCount = 0;
        this.nextMaterializationRetryAt = null;
        this.lastMaterializationError = null;
        this.lastMaterializationFailedAt = null;
    }

    /** occurrence 생성 성공 기록(spawner·즉시생성 공유). 성공하면 retry metadata 리셋. */
    public void recordSpawn(LocalDateTime targetArrivalAt) {
        this.lastSpawnedTargetArrivalAt = targetArrivalAt;
        this.materializationRetryCount = 0;
        this.nextMaterializationRetryAt = null;
        this.lastMaterializationError = null;
        this.lastMaterializationFailedAt = null;
    }

    /** 시스템오류 기록 + 지수 백오프(base·max 초). ACTIVE 유지 — 다음 백오프 도래 시 재시도. */
    public void recordMaterializationError(String reason, LocalDateTime now, long baseSeconds, long maxSeconds) {
        this.materializationRetryCount++;
        this.lastMaterializationError = reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason;
        this.lastMaterializationFailedAt = now;
        long delay = Math.min(baseSeconds * (1L << Math.min(materializationRetryCount - 1, 20)), maxSeconds);
        this.nextMaterializationRetryAt = now.plusSeconds(delay);
    }

    public void markCompleted() {
        this.status = RouteAlertScheduleStatus.COMPLETED;
    }

    public void markCanceled() {
        this.status = RouteAlertScheduleStatus.CANCELED;
    }
}
