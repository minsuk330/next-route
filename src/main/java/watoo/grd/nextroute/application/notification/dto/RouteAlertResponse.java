package watoo.grd.nextroute.application.notification.dto;

import lombok.Builder;
import lombok.Getter;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertPhase;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertReliability;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;

import java.time.LocalDateTime;

@Getter
@Builder
public class RouteAlertResponse {
    private Long id;
    private RouteAlertStatus status;
    private RouteAlertPhase phase;
    private Double startX;
    private Double startY;
    private String startName;
    private Double endX;
    private Double endY;
    private String endName;
    private LocalDateTime targetArrivalAt;
    private Integer userLeadMinutes;
    private Integer bufferMinutes;
    /** 등록 시 추정 출발시각. */
    private LocalDateTime baselineLeaveByAt;
    /** first check 로 확정된 추천 출발시각(first check 전 null). */
    private LocalDateTime recommendedLeaveByAt;
    private RouteAlertReliability reliability;
    private LocalDateTime expiresAt;

    public static RouteAlertResponse from(RouteArrivalAlert e) {
        return RouteAlertResponse.builder()
                .id(e.getId())
                .status(e.getStatus())
                .phase(e.getPhase())
                .startX(e.getStartX())
                .startY(e.getStartY())
                .startName(e.getStartName())
                .endX(e.getEndX())
                .endY(e.getEndY())
                .endName(e.getEndName())
                .targetArrivalAt(e.getTargetArrivalAt())
                .userLeadMinutes(e.getUserLeadMinutes())
                .bufferMinutes(e.getBufferMinutes())
                .baselineLeaveByAt(e.getBaselineLeaveByAt())
                .recommendedLeaveByAt(e.getRecommendedLeaveByAt())
                .reliability(e.getReliability())
                .expiresAt(e.getExpiresAt())
                .build();
    }
}
