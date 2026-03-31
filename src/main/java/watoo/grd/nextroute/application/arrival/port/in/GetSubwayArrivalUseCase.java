package watoo.grd.nextroute.application.arrival.port.in;

import watoo.grd.nextroute.application.arrival.dto.SubwayArrivalResponse;

import java.util.List;

public interface GetSubwayArrivalUseCase {
    List<SubwayArrivalResponse> getArrivals(String stationId);
}
