package watoo.grd.nextroute.application.bus;

import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheReadPort;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheWritePort;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트용 인메모리 실시간 버스 캐시(양 포트). Redis 없이 캐시 hit/miss·프라이밍을 재현한다.
 * 저장은 non-empty 만(실 어댑터 정책과 동일). TTL 은 흉내내지 않음 — 테스트가 필요 시 clear 로 만료 모사.
 */
public class InMemoryBusRealtimeCache implements BusRealtimeCacheReadPort, BusRealtimeCacheWritePort {

    private final Map<String, List<BusArrivalInfo>> arrivals = new ConcurrentHashMap<>();
    private final Map<String, List<BusPositionInfo>> positions = new ConcurrentHashMap<>();

    private static String arrKey(String stopId, String routeId, int seq) {
        return stopId + "|" + routeId + "|" + seq;
    }

    @Override
    public Optional<List<BusArrivalInfo>> getArrivals(String stopId, String routeId, int seq) {
        return Optional.ofNullable(arrivals.get(arrKey(stopId, routeId, seq)));
    }

    @Override
    public Optional<List<BusPositionInfo>> getPositions(String routeId) {
        return Optional.ofNullable(positions.get(routeId));
    }

    @Override
    public void putArrivals(String stopId, String routeId, int seq, List<BusArrivalInfo> data) {
        if (data == null || data.isEmpty()) return;
        arrivals.put(arrKey(stopId, routeId, seq), data);
    }

    @Override
    public void putPositions(String routeId, List<BusPositionInfo> data) {
        if (data == null || data.isEmpty()) return;
        positions.put(routeId, data);
    }

    public void clear() {
        arrivals.clear();
        positions.clear();
    }
}
