package watoo.grd.nextroute.application.notification.port.in;

import watoo.grd.nextroute.application.notification.dto.RouteAlertScheduleResponse;
import watoo.grd.nextroute.application.notification.dto.RouteAlertUpdateRequest;

public interface UpdateRouteAlertUseCase {
    RouteAlertScheduleResponse update(long userId, Long scheduleId, RouteAlertUpdateRequest request);
}
