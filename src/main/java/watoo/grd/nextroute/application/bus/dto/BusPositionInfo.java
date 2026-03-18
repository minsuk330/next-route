package watoo.grd.nextroute.application.bus.dto;

public record BusPositionInfo(
		String vehicleId,
		Double latitude,
		Double longitude,
		Integer stopSeq,
		Double sectionSpeed,
		Integer sectionOrder,
		String stopFlag,
		String dataTm,
		String plainNo,
		Integer busType,
		String lastStopId,
		String isRunning
) {}
