package watoo.grd.nextroute.application.bus.dto;

public record BusRouteStopMatchData(
		Integer seq,
		String stopId,
		String sectionId,
		Double sectionDistanceMeters,
		Double longitude,
		Double latitude
) {
}
