package watoo.grd.nextroute.application.notification.port.in;

import watoo.grd.nextroute.application.notification.dto.RouteAlertRequest;
import watoo.grd.nextroute.application.notification.dto.RouteAlertResponse;

public interface CreateRouteAlertUseCase {
    RouteAlertResponse create(long userId, RouteAlertRequest request);
}
