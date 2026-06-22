package watoo.grd.nextroute.application.bus.port.out;

import watoo.grd.nextroute.application.bus.dto.*;

import java.util.List;

public interface BusApiPort {

	List<BusRouteInfo> getBusRouteList(String searchKeyword);

	List<BusRouteInfo> getRouteInfo(String busRouteId);

	List<BusRouteStopInfo> getStationByRoute(String busRouteId);

	List<BusArrivalInfo> getArrInfoByRouteAll(String busRouteId);

	/** 특정 정류소·노선의 도착예정정보. ord = 정류소 경유순번(seq). */
	List<BusArrivalInfo> getArrInfoByStop(String stopId, String routeId, String ord);

	List<BusPositionInfo> getBusPosByRtid(String busRouteId);

	List<String> getBusRouteIds();

	BusRidershipFetchResult getBusRidershipByMonth(String month, int pageSize);
}
