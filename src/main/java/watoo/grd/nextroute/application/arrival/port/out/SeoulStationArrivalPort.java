package watoo.grd.nextroute.application.arrival.port.out;

import watoo.grd.nextroute.application.arrival.dto.SeoulStationArrival;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;

/** 서울 정류장 단위 도착({@code stationinfo/getStationByUid}). 검색용 한도·차단기를 따른다. */
public interface SeoulStationArrivalPort {

    BusQueryResult<SeoulStationArrival> getStationArrivals(String arsId, long timeoutMs);
}
