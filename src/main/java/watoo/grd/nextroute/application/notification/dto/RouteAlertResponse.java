package watoo.grd.nextroute.application.notification.dto;

import lombok.Builder;
import lombok.Getter;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;

import java.time.LocalDateTime;

@Getter
@Builder
public class RouteAlertResponse {
    private Long id;
    private RouteAlertStatus status;
    private Double startX;
    private Double startY;
    private String startName;
    private Double endX;
    private Double endY;
    private String endName;
    private LocalDateTime targetArrivalAt;
    private Integer userLeadMinutes;
    private Integer bufferMinutes;
    private Integer lastTotalDurationSec;
    private LocalDateTime leaveByAt;
    private LocalDateTime expiresAt;

    public static RouteAlertResponse from(RouteArrivalAlert e) {
        return RouteAlertResponse.builder()
                .id(e.getId())
                .status(e.getStatus())
                .startX(e.getStartX())
                .startY(e.getStartY())
                .startName(e.getStartName())
                .endX(e.getEndX())
                .endY(e.getEndY())
                .endName(e.getEndName())
                .targetArrivalAt(e.getTargetArrivalAt())
                .userLeadMinutes(e.getUserLeadMinutes())
                .bufferMinutes(e.getBufferMinutes())
                .lastTotalDurationSec(e.getLastTotalDurationSec())
                .leaveByAt(e.getLeaveByAt())
                .expiresAt(e.getExpiresAt())
                .build();
    }
}
