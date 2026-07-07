package watoo.grd.nextroute.application.notification.port.in;

import watoo.grd.nextroute.application.notification.dto.RouteAlertRequest;
import watoo.grd.nextroute.application.notification.dto.RouteAlertScheduleResponse;

public interface CreateRouteAlertUseCase {
    RouteAlertScheduleResponse create(long userId, RouteAlertRequest request);
}
