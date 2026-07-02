package watoo.grd.nextroute.application.notification.port.in;

import watoo.grd.nextroute.application.notification.dto.RouteAlertResponse;

import java.util.List;

public interface GetRouteAlertUseCase {
    List<RouteAlertResponse> getActive(long userId);
}
