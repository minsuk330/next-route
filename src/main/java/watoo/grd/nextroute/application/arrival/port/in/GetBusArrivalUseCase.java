package watoo.grd.nextroute.application.arrival.port.in;

import watoo.grd.nextroute.application.arrival.dto.BusArrivalResponse;

import java.util.List;

public interface GetBusArrivalUseCase {
    List<BusArrivalResponse> getArrivals(String stopId);
}
