package watoo.grd.nextroute.application.stopselection.port.in;

import watoo.grd.nextroute.application.stopselection.dto.StopRouteResult;

import java.util.List;

public interface GetStopRoutesUseCase {
    /** 정류장 경유 노선 목록. 정류장이 없으면 빈 리스트. */
    List<StopRouteResult> getStopRoutes(String stopId);
}
