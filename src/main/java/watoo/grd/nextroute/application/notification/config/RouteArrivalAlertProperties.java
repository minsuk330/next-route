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
}
