package watoo.grd.nextroute.application.notification.port.in;

import watoo.grd.nextroute.application.notification.dto.BusAlertResponse;

import java.util.List;

public interface GetBusAlertUseCase {
    List<BusAlertResponse> getActive(long userId);
}
