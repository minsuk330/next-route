package watoo.grd.nextroute.application.bus.port.out;

import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineData;
import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineRange;

import java.util.List;
import java.util.Optional;

public interface BusRouteSegmentPolylinePort {

	void replaceRouteSegments(String routeId, List<BusRouteSegmentPolylineData> segments);

	Optional<BusRouteSegmentPolylineRange> findRange(String routeId, int fromSeq, int toSeq);
}
