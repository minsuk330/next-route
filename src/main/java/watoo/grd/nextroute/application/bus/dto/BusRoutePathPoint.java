package watoo.grd.nextroute.application.bus.dto;

/** 서울시 버스 노선 경로의 원본 좌표점. x=경도, y=위도. */
public record BusRoutePathPoint(
		Integer sequence,
		Double longitude,
		Double latitude
) {
}
