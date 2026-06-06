package watoo.grd.nextroute.application.bus.dto;

public record BusPositionInfo(
		String vehicleId,
		Integer nextStopTime,
		Integer sectionOrder,
		Double sectionDistance,
		Double routeDistance,
		String stopFlag,
		String sectionId,
		String dataTm,
		String plainNo,
		Integer busType,
		Integer lastStopTime,
		String lastStopId,
		Double posX,
		Double posY,
		String isFullFlag,
		String isLastYn,
		Double fullSectionDistance,
		String nextStopId,
		Integer congestion,
		String turnStopId,
		Double gpsX,
		Double gpsY,
		String isRunYn
) {}
