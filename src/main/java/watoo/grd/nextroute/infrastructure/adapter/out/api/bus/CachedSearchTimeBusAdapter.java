package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheReadPort;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheWritePort;
import watoo.grd.nextroute.application.route.concurrent.SingleFlight;
import watoo.grd.nextroute.application.route.config.SearchTimeBusProperties;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
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

    private static final String TYPE_ARRIVAL = "arrival";
    private static final String TYPE_POSITION = "position";
    private static final String PROVIDER = "bus";

    private final SearchTimeBusAdapter delegate;
    private final BusRealtimeCacheReadPort cacheRead;
    private final BusRealtimeCacheWritePort cacheWrite;
    private final RouteSearchMetrics metrics;
    private final SearchTimeBusProperties properties;

    /**
     * 캐시 miss 뒤의 provider 호출을 키 단위로 병합한다(Step 3).
     * 도착은 {@code stopId:routeId:ord}, 위치는 {@code routeId} 가 키다 — provider 호출의 단위와 같다.
     */
    private final SingleFlight<String, BusQueryResult<BusArrivalInfo>> arrivalFlight = new SingleFlight<>();
    private final SingleFlight<String, BusQueryResult<BusPositionInfo>> positionFlight = new SingleFlight<>();

    public CachedSearchTimeBusAdapter(SearchTimeBusAdapter delegate,
                                      BusRealtimeCacheReadPort cacheRead,
                                      BusRealtimeCacheWritePort cacheWrite,
                                      RouteSearchMetrics metrics,
                                      SearchTimeBusProperties properties) {
        this.delegate = delegate;
        this.cacheRead = cacheRead;
        this.cacheWrite = cacheWrite;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Override
    public BusQueryResult<BusArrivalInfo> getArrInfoByStop(String stopId, String routeId, int ord) {
        return getArrInfoByStop(stopId, routeId, ord, Long.MAX_VALUE);
    }

    @Override
    public BusQueryResult<BusArrivalInfo> getArrInfoByStop(
            String stopId, String routeId, int ord, long timeoutMs) {
        Optional<List<BusArrivalInfo>> hit = cacheRead.getArrivals(stopId, routeId, ord);
        if (hit.isPresent()) {
            metrics.recordCacheAccess(TYPE_ARRIVAL, "hit");
            return BusQueryResult.cached(hit.get());   // provider 미호출 — cap·quota 미소모
        }
        metrics.recordCacheAccess(TYPE_ARRIVAL, "miss");

        String key = stopId + ":" + routeId + ":" + ord;
        SingleFlight.Outcome<BusQueryResult<BusArrivalInfo>> outcome = arrivalFlight.execute(key, () -> {
            BusQueryResult<BusArrivalInfo> fresh = timeoutMs == Long.MAX_VALUE
                    ? delegate.getArrInfoByStop(stopId, routeId, ord)
                    : delegate.getArrInfoByStop(stopId, routeId, ord, timeoutMs);
            if (fresh.isOk() && !fresh.data().isEmpty()) {
                cacheWrite.putArrivals(stopId, routeId, ord, fresh.data());
            }
            return fresh;
        }, joinWait(timeoutMs));

        return resolve(outcome, TYPE_ARRIVAL);
    }

    @Override
    public BusQueryResult<BusPositionInfo> getBusPosByRtid(String busRouteId) {
        return getBusPosByRtid(busRouteId, Long.MAX_VALUE);
    }

    @Override
    public BusQueryResult<BusPositionInfo> getBusPosByRtid(String busRouteId, long timeoutMs) {
        Optional<List<BusPositionInfo>> hit = cacheRead.getPositions(busRouteId);
        if (hit.isPresent()) {
            metrics.recordCacheAccess(TYPE_POSITION, "hit");
            return BusQueryResult.cached(hit.get());
        }
        metrics.recordCacheAccess(TYPE_POSITION, "miss");

        SingleFlight.Outcome<BusQueryResult<BusPositionInfo>> outcome = positionFlight.execute(busRouteId, () -> {
            BusQueryResult<BusPositionInfo> fresh = timeoutMs == Long.MAX_VALUE
                    ? delegate.getBusPosByRtid(busRouteId)
                    : delegate.getBusPosByRtid(busRouteId, timeoutMs);
            if (fresh.isOk() && !fresh.data().isEmpty()) {
                cacheWrite.putPositions(busRouteId, fresh.data());
            }
            return fresh;
        }, joinWait(timeoutMs));

        return resolve(outcome, TYPE_POSITION);
    }

    private long joinWait(long timeoutMs) {
        return timeoutMs == Long.MAX_VALUE
                ? properties.getJoinWaitMs()
                : Math.max(0, Math.min(properties.getJoinWaitMs(), timeoutMs));
    }

    /**
     * joiner 는 provider 를 호출하지 않았으므로 결과의 cacheHit 의미가 애매해진다.
     * 여기서는 "provider 호출을 하지 않았다"는 사실이 중요하므로 cached 로 표시한다(cap·quota 미소모).
     * 예산 안에 owner 결과를 못 받으면 LIMITED — 조회를 못 한 것이지 "버스 없음"이 아니다.
     */
    private <T> BusQueryResult<T> resolve(SingleFlight.Outcome<BusQueryResult<T>> outcome, String type) {
        metrics.recordSingleFlight(PROVIDER, type, outcome.role());
        return switch (outcome.role()) {
            case OWNER -> outcome.value();
            case JOINER -> outcome.hasValue() && outcome.value().isOk()
                    ? BusQueryResult.cached(outcome.value().data())
                    : outcome.value();
            case JOIN_TIMEOUT -> BusQueryResult.limited();
        };
    }
}
