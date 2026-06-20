package watoo.grd.nextroute.application.stopselection.port.in;

import watoo.grd.nextroute.application.stopselection.dto.RouteStopsResult;

public interface GetRouteStopsUseCase {
    /** 노선 경유 정류장 목록. 노선이 없으면 빈 stops. */
    RouteStopsResult getRouteStops(String routeId);
}
