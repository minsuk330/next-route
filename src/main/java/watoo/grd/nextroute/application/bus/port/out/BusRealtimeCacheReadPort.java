package watoo.grd.nextroute.application.bus.port.out;

import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;

import java.util.List;
import java.util.Optional;

/**
 * 짧은 TTL 실시간 버스 관측 캐시 조회 포트.
 *
 * <p>검색·공용 도착 조회가 provider 재호출 없이 최근 관측값을 재사용하기 위한 read model.
 * 캐시에 저장되는 값은 항상 non-empty이므로 {@link Optional#empty()}는 miss를,
 * present는 hit(비어있지 않은 데이터)을 의미한다. TTL/키/직렬화는 어댑터가 소유한다.
 */
public interface BusRealtimeCacheReadPort {

    Optional<List<BusArrivalInfo>> getArrivals(String stopId, String routeId, int seq);

    Optional<List<BusPositionInfo>> getPositions(String routeId);
}
