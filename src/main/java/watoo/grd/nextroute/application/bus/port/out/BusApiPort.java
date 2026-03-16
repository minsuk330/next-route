package watoo.grd.nextroute.application.bus.port.out;

import watoo.grd.nextroute.application.bus.dto.*;

import java.util.List;

public interface BusApiPort {

	List<BusRouteInfo> getBusRouteList(String searchKeyword);

	List<BusRouteInfo> getRouteInfo(String busRouteId);

	List<BusRouteStopInfo> getStationByRoute(String busRouteId);

	List<BusArrivalInfo> getArrInfoByRouteAll(String busRouteId);

	List<BusPositionInfo> getBusPosByRtid(String busRouteId);
}
