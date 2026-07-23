package watoo.grd.nextroute.application.bus.dto;

public record BusRoutePolylineBackfillResult(
		int total,
		int success,
		int skipped,
		int empty,
		int invalid,
		int failed,
		boolean aborted
) {
}
