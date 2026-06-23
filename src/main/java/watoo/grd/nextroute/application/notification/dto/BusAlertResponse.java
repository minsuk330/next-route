package watoo.grd.nextroute.application.notification.dto;

import lombok.Builder;
import lombok.Getter;
import watoo.grd.nextroute.domain.notification.entity.AlertStatus;
import watoo.grd.nextroute.domain.notification.entity.BusArrivalAlert;

import java.time.LocalDateTime;

@Getter
@Builder
public class BusAlertResponse {
    private Long id;
    private AlertStatus status;
    private String stopId;
    private String routeId;
    private String routeName;
    private String stopName;
    private LocalDateTime expiresAt;

    public static BusAlertResponse from(BusArrivalAlert entity) {
        return BusAlertResponse.builder()
                .id(entity.getId())
                .status(entity.getStatus())
                .stopId(entity.getStopId())
                .routeId(entity.getRouteId())
                .routeName(entity.getRouteName())
                .stopName(entity.getStopName())
                .expiresAt(entity.getExpiresAt())
                .build();
    }
}
