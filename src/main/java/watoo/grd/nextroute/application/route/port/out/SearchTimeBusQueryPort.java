package watoo.grd.nextroute.application.route.port.out;

import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;

import java.util.List;

/**
 * 경로검색 전용 버스 실시간 조회 포트.
 * 재시도 없음, 단축 timeout (~1.5s). 검색 레이턴시 보호용.
 */
public interface SearchTimeBusQueryPort {
    List<BusArrivalInfo> getArrInfoByStop(String stopId);
    List<BusPositionInfo> getBusPosByRtid(String busRouteId);
}
