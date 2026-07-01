package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheReadPort;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheWritePort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;

import java.util.List;
import java.util.Optional;

/**
 * 검색용 버스 API 캐시 데코레이터.
 *
 * <p>같은 stopId/routeId 중복 검색이 provider 호출을 키우지 않도록, 실시간 관측 캐시
 * ({@link BusRealtimeCacheReadPort}/{@link BusRealtimeCacheWritePort})를 앞단에 둔다.
 * {@link SearchTimeBusAdapter}를 감싸 {@code @Primary}로 주입된다.
 *
 * <p>캐시 hit은 provider 미호출이라 {@link SearchTimeBusQueryPort.BusQueryResult#cached}로 표시한다
 * (검색 호출 cap·quota 미소모). 캐시 저장/우회 정책(TTL·빈 결과·장애 graceful)은 캐시 어댑터가 소유한다.
 */
@Slf4j
@Primary
@Component
public class CachedSearchTimeBusAdapter implements SearchTimeBusQueryPort {

    private final SearchTimeBusAdapter delegate;
    private final BusRealtimeCacheReadPort cacheRead;
    private final BusRealtimeCacheWritePort cacheWrite;

    public CachedSearchTimeBusAdapter(SearchTimeBusAdapter delegate,
                                      BusRealtimeCacheReadPort cacheRead,
                                      BusRealtimeCacheWritePort cacheWrite) {
        this.delegate = delegate;
        this.cacheRead = cacheRead;
        this.cacheWrite = cacheWrite;
    }

    @Override
    public BusQueryResult<BusArrivalInfo> getArrInfoByStop(String stopId, String routeId, int ord) {
        Optional<List<BusArrivalInfo>> hit = cacheRead.getArrivals(stopId, routeId, ord);
        if (hit.isPresent()) {
            return BusQueryResult.cached(hit.get());   // provider 미호출 — cap·quota 미소모
        }
        BusQueryResult<BusArrivalInfo> fresh = delegate.getArrInfoByStop(stopId, routeId, ord);
        if (fresh.isOk() && !fresh.data().isEmpty()) {
            cacheWrite.putArrivals(stopId, routeId, ord, fresh.data());
        }
        return fresh;
    }

    @Override
    public BusQueryResult<BusPositionInfo> getBusPosByRtid(String busRouteId) {
        Optional<List<BusPositionInfo>> hit = cacheRead.getPositions(busRouteId);
        if (hit.isPresent()) {
            return BusQueryResult.cached(hit.get());
        }
        BusQueryResult<BusPositionInfo> fresh = delegate.getBusPosByRtid(busRouteId);
        if (fresh.isOk() && !fresh.data().isEmpty()) {
            cacheWrite.putPositions(busRouteId, fresh.data());
        }
        return fresh;
    }
}
