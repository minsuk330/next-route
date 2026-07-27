package watoo.grd.nextroute.application.bus.dto;

import java.time.OffsetDateTime;

public record BusRoutePolylineSnapshot(
		String routeId,
		BusRoutePolylineData data,
		OffsetDateTime fetchedAt
) {
}
