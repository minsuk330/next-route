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
    private final CrossInstanceFlightPort flight;

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
                                      SearchTimeBusProperties properties,
                                      CrossInstanceFlightPort flight) {
        this.delegate = delegate;
        this.cacheRead = cacheRead;
        this.cacheWrite = cacheWrite;
        this.metrics = metrics;
        this.properties = properties;
        this.flight = flight;
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
                    }
                    return fresh;
                }, () -> cacheRead.getPositions(busRouteId), BusPositionInfo.class, timeoutMs),
                joinWait(timeoutMs));

        return resolve(outcome, TYPE_POSITION);
    }

    /**
     * 서버 사이 single-flight(PR-2). JVM 안 SingleFlight 의 owner 만 여기 들어온다(2단계).
     *
     * <p>리더: 잠금을 잡은 뒤 캐시를 <b>한 번 더</b> 읽는다 — 다른 서버가 방금 채웠으면 호출 없이 끝(double-check).
     * 공급자를 부른 뒤 outcome 과 data 를 토큰별 결과 키에 남기고 잠금을 푼다.
     * 대기자: 보유자 토큰의 결과 키를 {@code pollMs} 마다 본다. OK 는 cached 로, BLOCKED(공유 차단기)는 그대로 돌려준다.
     * LIMITED·ERROR 는 리더 서버의 <b>자기 사정</b>(JVM 세마포어 포화, 일시 오류)일 수 있으므로 공유하지 않고 직접 부른다
     * (플래그 꺼짐 때와 같은 가용성). 잠금이 만료돼 새 리더가 섰으면 그 토큰을 따라간다. 잠금이 사라졌는데 결과가 없으면
     * (리더 사망) <b>스스로 리더를 시도</b>한다 — 잡으면 직접 부르고, 남이 잡았으면 그 토큰을 따라간다. 상한 초과·Redis 오류는
     * LIMITED. 잠금 획득 자체가 Redis 오류면 직접 부른다.
     *
     * <p>잠금 수명은 갱신하지 않는다 — 기한 없는 호출은 세마포어 대기 + connect + read + Redis 왕복을 더한 상한(기본 6초)으로
     * 잡고, 넘기면 두 번째 리더가 한 번 더 부르는 것을 감수한다(정합성 문제는 아니다, 결과 키가 토큰별이라).
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
        CrossInstanceFlightPort.Lead lead;
        try {
            lead = flight.tryLead(type, key, lockTtl);
        } catch (RuntimeException e) {
            log.warn("[CrossFlight] 잠금 시도 실패 — 직접 부른다 [{}:{}]: {}", type, key, e.getMessage());
            metrics.recordCrossInstanceFlight(PROVIDER, type, "redis_error");
            return loader.get();
        }
        if (lead.mine()) {
            return lead(type, key, lead.token(), loader, cacheProbe, Duration.ofMillis(Math.max(100, ci.getResultTtlMs())));
        }
        return await(type, key, lead.token(), loader, cacheProbe, elementType, lockTtl,
                Duration.ofMillis(Math.max(100, ci.getResultTtlMs())), waitMs, Math.max(1, ci.getPollMs()));
    }

    private <T> BusQueryResult<T> lead(String type, String key, String token, Supplier<BusQueryResult<T>> loader,
                                       Supplier<Optional<List<T>>> cacheProbe, Duration resultTtl) {
        Optional<List<T>> again = cacheProbe.get();
        if (again.isPresent()) {
            metrics.recordCrossInstanceFlight(PROVIDER, type, "leader_recheck_hit");
            finishQuietly(type, key, token, Outcome.OK, again.get(), resultTtl);
            return BusQueryResult.cached(again.get());
        }
        BusQueryResult<T> fresh;
        try {
            fresh = loader.get();
        } catch (RuntimeException e) {
            finishQuietly(type, key, token, Outcome.ERROR, List.of(), resultTtl);
            throw e;
        }
        metrics.recordCrossInstanceFlight(PROVIDER, type, "leader");
        finishQuietly(type, key, token, fresh.outcome(), fresh.data(), resultTtl);
        return fresh;
    }

    private void finishQuietly(String type, String key, String token, Outcome outcome, List<?> data,
                               Duration resultTtl) {
        try {
            flight.finish(type, key, token, outcome, data, resultTtl);
        } catch (RuntimeException e) {
            // 결과를 못 남기면 대기자는 잠금 만료까지 기다렸다 캐시를 보고, 없으면 LIMITED. 리더의 결과 자체는 유효하다.
            log.warn("[CrossFlight] 결과 기록 실패 [{}:{}]: {}", type, key, e.getMessage());
            metrics.recordCrossInstanceFlight(PROVIDER, type, "redis_error");
        }
    }

    private <T> BusQueryResult<T> await(String type, String key, String holder, Supplier<BusQueryResult<T>> loader,
                                        Supplier<Optional<List<T>>> cacheProbe, Class<T> elementType,
                                        Duration lockTtl, Duration resultTtl, long waitMs, long pollMs) {
        long deadline = System.nanoTime() + waitMs * 1_000_000L;
        while (true) {
            try {
                Optional<CrossInstanceFlightPort.Peek<T>> peek = holder == null
                        ? Optional.empty()
                        : flight.peekResult(type, key, holder, elementType);
                if (peek.isEmpty()) {
                    Optional<String> current = flight.currentHolder(type, key);
                    if (current.isEmpty()) {
                        // 리더가 "결과 쓰기 → 잠금 해제" 사이에 있었을 수 있다 — 한 번 더 본 뒤 사망으로 판정.
                        peek = holder == null ? Optional.empty() : flight.peekResult(type, key, holder, elementType);
                        if (peek.isEmpty()) {
                            // 리더 사망(또는 결과 기록 실패). 스스로 리더를 시도한다 — 잡으면 직접 부르고, 남이 먼저 잡았으면 따라간다.
                            CrossInstanceFlightPort.Lead retake = flight.tryLead(type, key, lockTtl);
                            if (retake.mine()) {
                                metrics.recordCrossInstanceFlight(PROVIDER, type, "waiter_takeover");
                                return lead(type, key, retake.token(), loader, cacheProbe, resultTtl);
                            }
                            holder = retake.token();
                        }
                    } else if (holder == null || !current.get().equals(holder)) {
                        // 잠금이 만료돼 새 리더가 섰다 — 그 토큰의 결과를 기다린다.
                        holder = current.get();
                    }
                }
                if (peek.isPresent()) {
                    CrossInstanceFlightPort.Peek<T> r = peek.get();
                    return switch (r.outcome()) {
                        case OK -> {
                            metrics.recordCrossInstanceFlight(PROVIDER, type, "waiter_result");
                            yield BusQueryResult.cached(r.data());
                        }
                        case BLOCKED -> {
                            metrics.recordCrossInstanceFlight(PROVIDER, type, "waiter_result");
                            yield BusQueryResult.blocked();
                        }
                        case LIMITED, ERROR -> {
                            // 리더 서버의 자기 사정(세마포어 포화·일시 오류)일 수 있다 — 직접 불러 본다.
                            metrics.recordCrossInstanceFlight(PROVIDER, type, "waiter_fallback");
                            yield loader.get();
                        }
                    };
                }
            } catch (RuntimeException e) {
                log.warn("[CrossFlight] 결과 조회 실패 [{}:{}]: {}", type, key, e.getMessage());
                metrics.recordCrossInstanceFlight(PROVIDER, type, "redis_error");
                return BusQueryResult.limited();
            }
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                metrics.recordCrossInstanceFlight(PROVIDER, type, "waiter_timeout");
                return BusQueryResult.limited();
            }
            try {
                Thread.sleep(Math.min(pollMs, remainingMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return BusQueryResult.limited();
            }
        }
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
