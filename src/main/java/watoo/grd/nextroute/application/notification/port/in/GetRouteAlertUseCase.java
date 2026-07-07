package watoo.grd.nextroute.application.notification.port.in;

import watoo.grd.nextroute.application.notification.dto.RouteAlertScheduleResponse;

import java.util.List;

public interface GetRouteAlertUseCase {
    List<RouteAlertScheduleResponse> getActive(long userId);
}
