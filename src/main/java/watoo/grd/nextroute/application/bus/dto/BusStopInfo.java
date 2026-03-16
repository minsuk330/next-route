package watoo.grd.nextroute.application.bus.dto;

public record BusStopInfo(
		String stopId,
		String stopName,
		String arsId,
		Double latitude,
		Double longitude
) {}
