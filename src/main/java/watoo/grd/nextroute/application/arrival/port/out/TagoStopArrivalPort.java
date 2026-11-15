package watoo.grd.nextroute.application.arrival.port.out;

import watoo.grd.nextroute.application.arrival.dto.TagoStopArrival;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;

/** TAGO 정류장 단위 도착({@code getSttnAcctoArvlPrearngeInfoList}). 버스 한 대 = 한 줄. */
public interface TagoStopArrivalPort {

    BusQueryResult<TagoStopArrival> getStopArrivals(String cityCode, String nodeId);
}
