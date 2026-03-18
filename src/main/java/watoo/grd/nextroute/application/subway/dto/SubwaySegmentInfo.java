package watoo.grd.nextroute.application.subway.dto;

public record SubwaySegmentInfo(
		String lineId,
		String departStationName,
		String arriveStationName,
		Double distance,
		Double travelTime,
		Integer seq
) {}
