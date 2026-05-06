package watoo.grd.nextroute.application.nearby.port.in;

import watoo.grd.nextroute.application.nearby.dto.NearbyBusStopResult;

import java.util.List;

public interface GetNearbyBusStopsUseCase {
    List<NearbyBusStopResult> getNearbyBusStops(double lat, double lng, int limit);
}
