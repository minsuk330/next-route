package watoo.grd.nextroute.application.subway.dto;

public record SubwayTimetableInfo(
		String subwayStationId,
		String subwayStationNm,
		String subwayRouteId,
		String endSubwayStationNm,
		String depTime,
		String arrTime,
		String dailyTypeCode,
		String upDownTypeCode
) {}
