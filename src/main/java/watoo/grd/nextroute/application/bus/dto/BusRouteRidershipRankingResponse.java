package watoo.grd.nextroute.application.bus.dto;

import java.util.List;

public record BusRouteRidershipRankingResponse(
		String month,
		int totalRowCount,
		int fetchedRowCount,
		List<BusRouteRidershipRank> rankings
) {}
