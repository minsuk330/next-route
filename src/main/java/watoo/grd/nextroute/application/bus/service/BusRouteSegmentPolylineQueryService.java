package watoo.grd.nextroute.application.bus.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineRange;
import watoo.grd.nextroute.application.bus.port.in.GetBusRouteSegmentPolylineUseCase;
import watoo.grd.nextroute.application.bus.port.out.BusRouteSegmentPolylinePort;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BusRouteSegmentPolylineQueryService implements GetBusRouteSegmentPolylineUseCase {

	private final BusRouteSegmentPolylinePort port;

	@Override
	public Optional<BusRouteSegmentPolylineRange> getRange(String routeId, int fromSeq, int toSeq) {
		if (fromSeq < 1 || toSeq <= fromSeq) {
			throw new IllegalArgumentException("toSeq must be greater than fromSeq");
		}
		return port.findRange(routeId, fromSeq, toSeq);
	}
}
