package watoo.grd.nextroute.application.bus.dto;

public record BusRouteInfo(
		String routeId,
		String routeName,
		Integer routeType,
		String startStation,
		String endStation,
		Integer term,
		String firstBusTime,
		String lastBusTime,
		String companyName,
		Double totalDistance
) {}
