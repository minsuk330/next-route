package watoo.grd.nextroute.domain.bus.service;

import java.util.List;

/** 지도 응답에 그대로 사용할 수 있는 x=경도, y=위도 좌표 배열. */
public record BusRoutePolylineData(List<Point> points) {

	public BusRoutePolylineData {
		points = points == null ? List.of() : List.copyOf(points);
	}

	public int size() {
		return points.size();
	}

	public record Point(double x, double y) {
	}
}
