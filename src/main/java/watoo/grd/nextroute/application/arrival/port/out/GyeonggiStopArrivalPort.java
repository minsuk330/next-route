package watoo.grd.nextroute.application.arrival.port.out;

import watoo.grd.nextroute.application.arrival.dto.GyeonggiStopArrival;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;

/** 경기 정류장 단위 도착({@code getBusArrivalListv2}). 노선 단위와 다른 몫·차단 이름을 쓴다. */
public interface GyeonggiStopArrivalPort {

    BusQueryResult<GyeonggiStopArrival> getStationArrivals(String stationId);
}
