package watoo.grd.nextroute.domain.bus.repository;

public record BusRouteStopMatchData(
		Integer seq,
		String stopId,
		String sectionId,
		Double sectionDistanceMeters,
		Double longitude,
		Double latitude
) {
}
