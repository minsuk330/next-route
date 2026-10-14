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

    /**
     * 이 역의 캐시를 지운다. 전 노선 스냅숏에서 이 역이 바뀐 것을 봤을 때, 곧 다시 평가될 여정이 더 낡은
     * 역별 캐시를 읽고 "변화 없음"이라 하지 않게 한다(#415-3). 실패해도 예외를 내지 않는다.
     */
    void evict(String stationName);
}
