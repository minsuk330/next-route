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

    /** 앞당김 감지 임계(분): newLeaveBy ≤ prevLeaveBy − 이 값이면 EARLIER 발행. (PR2) */
    private int earlierThresholdMinutes = 5;

    /** 앞당김 알림 per-alert 상한. (PR2) */
    private int earlierCapCount = 3;

    /** alert 당 재탐색 성공 호출 총 상한. (PR2) */
    private int researchCountCap = 20;
}
