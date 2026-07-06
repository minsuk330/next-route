package watoo.grd.nextroute.application.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 목표 도착시간 기반 출발 알림 설정. PR1 은 구독/계산 값만 사용(재탐색/발송은 후속 PR). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "notification.route-arrival")
public class RouteArrivalAlertProperties {

    /** 기본 안전 버퍼(분). 요청이 값을 주지 않으면 사용. */
    private int defaultBufferMinutes = 5;

    /** 구독 만료 여유(분): expiresAt = targetArrivalAt + 이 값. */
    private int expiresAfterTargetMinutes = 30;

    /** term 미확보 버스 구간 STATIC 대기 = 이 값/2(분). */
    private int defaultHeadwayMinutes = 10;

    /** firstCheckAt 기본 여유(분). */
    private int firstCheckBaseMarginMinutes = 3;
    /** firstCheckAt term 비례 여유 계수(배차 변동 흡수). */
    private double firstCheckHeadwayFactor = 0.5;
    /** firstCheckAt term 비례 여유 상한(분). 너무 이르면 실시간 미확정 → NO_VEHICLE 방지. */
    private int firstCheckMaxMarginMinutes = 20;

    // ── 체크 스케줄러(PR-B) ──────────────────────────────────────────────────
    /** 체크 스케줄러 활성화. 기본 off. */
    private boolean schedulerEnabled = false;
    /** 체크 스캔 주기(ms). */
    private long checkIntervalMs = 30000;
    /** final check 리드(분): finalCheckAt = recommendedLeaveBy − 이 값. userLead ≤ 이 값 → final check 생략. */
    private int finalCheckLeadMinutes = 5;
    /** 변경 임계(분): |final − lastNotified| ≥ 이 값 → CHANGE. */
    private int changeThresholdMinutes = 5;
    /** 스냅샷 STALE/OUT_OF_SERVICE boarding 비율 초과 시 CHANGE 억제. */
    private double snapshotStaleThreshold = 0.5;

    // ── 발송 디스패처(PR-C) ──────────────────────────────────────────────────
    /** 디스패처 활성화. 기본 off. */
    private boolean dispatchEnabled = false;
    /** 디스패치 스캔 주기(ms). */
    private long dispatchIntervalMs = 15000;
    /** 스캔당 처리 상한. */
    private int dispatchBatchSize = 100;
    /** PROCESSING 고착 reclaim 임계(분). */
    private int claimTimeoutMinutes = 5;
    /** transient 실패 최대 시도. 초과 시 FAILED. */
    private int maxAttempts = 3;
    /** transient 실패 재시도 백오프(초). */
    private int backoffSeconds = 60;
}
