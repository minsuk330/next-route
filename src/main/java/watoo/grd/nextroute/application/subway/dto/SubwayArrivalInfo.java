package watoo.grd.nextroute.application.subway.dto;

public record SubwayArrivalInfo(
		String stationId,
		String stationName,
		String lineId,
		String direction,
		String prevStationId,
		String nextStationId,
		Integer transferCount,
		String ordkey,
		String transferLines,
		String transferStations,
		String trainType,
		Integer arrivalSeconds,
		String trainNo,
		String destinationId,
		String destinationName,
		String currentMessage,
		String arrivalCode,
		String subwayId,
		String arrivalMsg3,
		String receivedAt,
		String trainLineName,
		String lastTrainYn
) {}
