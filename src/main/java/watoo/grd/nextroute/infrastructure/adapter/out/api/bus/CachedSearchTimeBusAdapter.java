package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import org.springframework.beans.factory.annotation.Autowired;
import watoo.grd.nextroute.application.bus.port.out.BusRouteObservationHistoryPort;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheReadPort;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheWritePort;
import watoo.grd.nextroute.application.route.concurrent.CrossInstanceFlightRunner;
import watoo.grd.nextroute.application.route.concurrent.SingleFlight;
import watoo.grd.nextroute.application.route.config.SearchTimeBusProperties;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.CrossInstanceFlightPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

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
    private final BusRouteObservationHistoryPort observationHistory;
    private final CrossInstanceFlightPort flight;

    /**
     * 캐시 miss 뒤의 provider 호출을 키 단위로 병합한다(Step 3).
     * 도착은 {@code stopId:routeId:ord}, 위치는 {@code routeId} 가 키다 — provider 호출의 단위와 같다.
     */
    private final SingleFlight<String, BusQueryResult<BusArrivalInfo>> arrivalFlight = new SingleFlight<>();
    private final SingleFlight<String, BusQueryResult<BusPositionInfo>> positionFlight = new SingleFlight<>();

    @Autowired
    public CachedSearchTimeBusAdapter(SearchTimeBusAdapter delegate,
                                      BusRealtimeCacheReadPort cacheRead,
                                      BusRealtimeCacheWritePort cacheWrite,
                                      RouteSearchMetrics metrics,
                                      SearchTimeBusProperties properties,
                                      BusRouteObservationHistoryPort observationHistory,
                                      CrossInstanceFlightPort flight) {
        this.delegate = delegate;
        this.cacheRead = cacheRead;
        this.cacheWrite = cacheWrite;
        this.metrics = metrics;
        this.properties = properties;
        this.observationHistory = observationHistory;
        this.flight = flight;
    }

    /** ML 노선 이력을 쌓지 않는 생성자(테스트용). */
    public CachedSearchTimeBusAdapter(SearchTimeBusAdapter delegate,
                                      BusRealtimeCacheReadPort cacheRead,
                                      BusRealtimeCacheWritePort cacheWrite,
                                      RouteSearchMetrics metrics,
                                      SearchTimeBusProperties properties,
                                      CrossInstanceFlightPort flight) {
        this(delegate, cacheRead, cacheWrite, metrics, properties, BusRouteObservationHistoryPort.NONE, flight);
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
        SingleFlight.Outcome<BusQueryResult<BusArrivalInfo>> outcome = arrivalFlight.execute(key,
                () -> crossInstance(TYPE_ARRIVAL, key, () -> {
                    BusQueryResult<BusArrivalInfo> fresh = timeoutMs == Long.MAX_VALUE
                            ? delegate.getArrInfoByStop(stopId, routeId, ord)
                            : delegate.getArrInfoByStop(stopId, routeId, ord, timeoutMs);
                    if (fresh.isOk() && !fresh.data().isEmpty()) {
                        cacheWrite.putArrivals(stopId, routeId, ord, fresh.data());
                    }
                    return fresh;
                }, () -> cacheRead.getArrivals(stopId, routeId, ord), BusArrivalInfo.class, timeoutMs),
                joinWait(timeoutMs));

        return resolve(outcome, TYPE_ARRIVAL);
    }

    /** 감시자 전용이라 캐시를 거치지 않는다 — 부르는 쪽이 받은 값으로 캐시를 직접 채운다. */
    @Override
    public BusQueryResult<BusArrivalInfo> getArrInfoByRouteAll(String routeId, long timeoutMs) {
        return delegate.getArrInfoByRouteAll(routeId, timeoutMs);
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

        SingleFlight.Outcome<BusQueryResult<BusPositionInfo>> outcome = positionFlight.execute(busRouteId,
                () -> crossInstance(TYPE_POSITION, busRouteId, () -> {
                    BusQueryResult<BusPositionInfo> fresh = timeoutMs == Long.MAX_VALUE
                            ? delegate.getBusPosByRtid(busRouteId)
                            : delegate.getBusPosByRtid(busRouteId, timeoutMs);
                    if (fresh.isOk() && !fresh.data().isEmpty()) {
                        cacheWrite.putPositions(busRouteId, fresh.data());
                        // 새 관측만 ML 노선 이력에 덧붙인다(캐시 hit 은 이미 쌓인 관측)
                        observationHistory.append(busRouteId, fresh.data());
                    }
                    return fresh;
                }, () -> cacheRead.getPositions(busRouteId), BusPositionInfo.class, timeoutMs),
                joinWait(timeoutMs));

        return resolve(outcome, TYPE_POSITION);
    }

    /**
     * 서버 사이 single-flight(PR-2). JVM 안 SingleFlight 의 owner 만 여기 들어온다(2단계). 절차는
     * {@link CrossInstanceFlightRunner}(PR-4 에서 경기·TAGO 와 공용으로 뽑음)가 맡고, 여기서는 잠금 수명과 대기 상한만 정한다.
     *
     * <p>잠금 수명은 갱신하지 않는다 — 기한 없는 호출은 세마포어 대기 + connect + read + Redis 왕복을 더한 상한(기본 6초)으로
     * 잡고, 넘기면 두 번째 리더가 한 번 더 부르는 것을 감수한다(결과 키가 토큰별이라 정합성 문제는 아니다).
     */
    private <T> BusQueryResult<T> crossInstance(String type, String key, Supplier<BusQueryResult<T>> loader,
                                                Supplier<Optional<List<T>>> cacheProbe, Class<T> elementType,
                                                long timeoutMs) {
        SearchTimeBusProperties.CrossInstance ci = properties.getCrossInstance();
        if (!ci.isEnabled()) {
            return loader.get();
        }
        boolean unbounded = timeoutMs == Long.MAX_VALUE;
        Duration lockTtl = Duration.ofMillis(unbounded
                ? Math.max(1000, ci.getUnboundedLockTtlMs())
                : timeoutMs + Math.max(0, ci.getLockMarginMs()));
        // 대기 상한: 기한 있는 호출은 JVM joiner 와 같은 joinWait, 기한 없는 호출은 리더가 걸릴 수 있는 최대(잠금 수명).
        long waitMs = unbounded ? lockTtl.toMillis() : joinWait(timeoutMs);
        CrossInstanceFlightRunner runner = new CrossInstanceFlightRunner(flight,
                (t, role) -> metrics.recordCrossInstanceFlight(PROVIDER, t, role),
                ci.getPollMs(), Duration.ofMillis(Math.max(100, ci.getResultTtlMs())));
        return runner.run(type, key, loader, cacheProbe, elementType, lockTtl, waitMs);
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
