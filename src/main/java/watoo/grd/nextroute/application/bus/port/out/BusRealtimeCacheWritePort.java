package watoo.grd.nextroute.application.bus.port.out;

import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;

import java.util.List;

/**
 * 짧은 TTL 실시간 버스 관측 캐시 저장 포트.
 *
 * <p>검색 hot path의 miss 시 write, collector(스케줄러) 수집 직후 프라이밍에 공통으로 쓰인다.
 * 빈 데이터는 저장하지 않고(빈 결과 고착 방지), TTL/키/직렬화·저장 실패 graceful 처리는 어댑터가 담당한다.
 */
public interface BusRealtimeCacheWritePort {

    void putArrivals(String stopId, String routeId, int seq, List<BusArrivalInfo> data);

    void putPositions(String routeId, List<BusPositionInfo> data);
}
