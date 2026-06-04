package watoo.grd.nextroute.application.bus.dto;

public record BusRouteRidershipRank(
		int rank,
		String routeNo,
		String routeName,
		long getOnTotal,
		long getOffTotal,
		long totalUsage,
		int rowCount
) {}
