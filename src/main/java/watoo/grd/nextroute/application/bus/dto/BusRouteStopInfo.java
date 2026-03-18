package watoo.grd.nextroute.application.bus.dto;

public record BusRouteStopInfo(
		String routeId,
		Integer seq,
		String sectionId,
		String stopId,
		String stopName,
		String arsId,
		Double latitude,
		Double longitude,
		Double sectionDistance,
		String direction,
		String transferYn,
		String stationNo,
		String beginTm,
		String lastTm,
		String turnStopId,
		Double sectionSpeed
) {}
