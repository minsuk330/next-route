package watoo.grd.nextroute.application.subway.port.out;

import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;

import java.util.List;
import java.util.Optional;

/**
 * 역별 지하철 실시간 응답 캐시 포트.
 *
 * <p>합치는 것은 <b>원시 응답</b>까지다. 어느 열차를 탈 수 있는지 고르는 일은 사용자마다 준비 시각이
 * 달라 답이 다르므로 캐시 뒤에 남는다.
 */
public interface SubwayStationArrivalCachePort {

    Optional<List<SubwayArrivalInfo>> find(String stationName);

    void put(String stationName, List<SubwayArrivalInfo> arrivals);
}
