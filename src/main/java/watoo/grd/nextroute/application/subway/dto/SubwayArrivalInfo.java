package watoo.grd.nextroute.application.subway.dto;

public record SubwayArrivalInfo(
		String stationId,
		String stationName,
		String lineId,
		String direction,
		Integer arrivalSeconds,
		String trainNo,
		String destinationName,
		String currentMessage,
		String arrivalCode
) {}
