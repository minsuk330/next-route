package watoo.grd.nextroute.application.notification.port.in;

import watoo.grd.nextroute.application.notification.dto.BusAlertRequest;
import watoo.grd.nextroute.application.notification.dto.BusAlertResponse;

public interface CreateBusAlertUseCase {
    BusAlertResponse create(long userId, BusAlertRequest request);
}
