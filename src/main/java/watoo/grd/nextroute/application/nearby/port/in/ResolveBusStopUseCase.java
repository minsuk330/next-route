package watoo.grd.nextroute.application.nearby.port.in;

import watoo.grd.nextroute.application.nearby.dto.BusStopResolveResult;

public interface ResolveBusStopUseCase {
    BusStopResolveResult resolveBusStop(double lat, double lng, String name);
}
