package watoo.grd.nextroute.application.bus.port.in;

import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineRange;

import java.util.Optional;

public interface GetBusRouteSegmentPolylineUseCase {

	Optional<BusRouteSegmentPolylineRange> getRange(String routeId, int fromSeq, int toSeq);
}
