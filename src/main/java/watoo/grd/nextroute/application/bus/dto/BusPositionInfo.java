package watoo.grd.nextroute.application.bus.dto;

public record BusPositionInfo(
		String vehicleId,
		Double tmX,
		Double tmY,
		Integer sectionOrder,
		Double sectionDistance,
		String stopFlag,
		String sectionId,
		String dataTm,
		String plainNo,
		Integer busType,
		String lastStopId,
		Double posX,
		Double posY,
		String apiRouteId,
		Integer congestion
) {}
