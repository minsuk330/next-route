package watoo.grd.nextroute.application.arrival.port.out;

import watoo.grd.nextroute.application.arrival.dto.TagoBusArrival;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;

/**
 * 서울·경기 밖 버스도착 조회(국토교통부 TAGO). id 는 OTP 접두사 포함 값({@code BSB167850202}),
 * cityCode 는 노선으로 정한다({@code TagoCityCodeLookup}, #267).
 *
 * <p>OK 의 data 는 다가오는 버스들(순서 보장 없음, 빈 리스트 = 다가오는 버스 없음).
 */
public interface TagoBusArrivalPort {

    BusQueryResult<TagoBusArrival> getArrivals(String cityCode, String nodeId, String routeId);
}
