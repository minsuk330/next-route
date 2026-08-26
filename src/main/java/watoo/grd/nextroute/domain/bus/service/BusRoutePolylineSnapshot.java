package watoo.grd.nextroute.domain.bus.service;

import java.time.OffsetDateTime;

public record BusRoutePolylineSnapshot(
		String routeId,
		BusRoutePolylineData data,
		OffsetDateTime fetchedAt
) {
}
