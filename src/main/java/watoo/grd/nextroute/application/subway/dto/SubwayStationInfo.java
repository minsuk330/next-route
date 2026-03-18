package watoo.grd.nextroute.application.subway.dto;

public record SubwayStationInfo(
		String stationId,
		String stationName,
		String lineId,
		String lineName,
		Double latitude,
		Double longitude
) {}
