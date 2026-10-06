package watoo.grd.nextroute.application.regionalbus.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.regionalbus.config.RegionalBusCatalogProperties;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusCatalogLoadResult;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop;
import watoo.grd.nextroute.application.regionalbus.dto.TagoCity;
import watoo.grd.nextroute.application.regionalbus.dto.TagoRoute;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteSourcePort;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteStopStore;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusSourceException;
import watoo.grd.nextroute.application.route.mapping.TagoCityCodeLookup;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 경기·TAGO 노선 정류장 표 적재(#267).
 *
 * <p>순서: TAGO 도시 목록 → 도시별 노선 목록(경기 노선도 {@code GGB} 로 여기 있다) → 노선마다 정류장.
 * 경기 노선만 경기 API 로 받는다 — 경기 실시간과 같은 순번 체계라서.
 *
 * <h2>실패를 다루는 방식</h2>
 * <ul>
 *   <li>노선 하나 실패 → 그 노선은 옛 값 그대로 두고 넘어간다. 저장은 노선 단위 통째 교체라 반쯤 쓴 노선이 없다.</li>
 *   <li>정류장 0건 → 실패로 치지 않지만 옛 값을 지우지도 않는다. 일시적 빈 응답일 수 있다.</li>
 *   <li>포털 공통 오류(키 미등록·한도 초과) → 그날 계속 실패하므로 남은 작업을 버리고 멈춘다.</li>
 *   <li>폐선 정리는 "노선 목록에 더는 없는 노선"만 지운다. 목록은 한 번이라도 실패하면 통째로 멈추므로
 *       온전한 목록일 때만 정리한다. 이번에 0건·실패였던 노선은 목록에 있으니 옛 값이 남는다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegionalBusCatalogLoadService {

    private final RegionalBusRouteSourcePort source;
    private final RegionalBusRouteStopStore store;
    private final RegionalBusCatalogProperties properties;
    private final TagoCityCodeLookup lookup;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public boolean isEmpty() {
        return store.isEmpty();
    }

    public RegionalBusCatalogLoadResult load() {
        if (!running.compareAndSet(false, true)) {
            log.info("[RegionalBusCatalog] already running; skip");
            return RegionalBusCatalogLoadResult.skippedRun();
        }
        try {
            RegionalBusCatalogLoadResult result = run();
            log.info("[RegionalBusCatalog] routes={} loaded={} empty={} failed={} pruned={} aborted={} elapsed={}s",
                    result.routes(), result.loaded(), result.empty(), result.failed(), result.pruned(),
                    result.aborted(), result.elapsed().toSeconds());
            lookup.refresh();
            return result;
        } finally {
            running.set(false);
        }
    }

    private RegionalBusCatalogLoadResult run() {
        Instant startedAt = clock.instant();
        List<TagoRoute> routes;
        try {
            routes = listRoutes();
        } catch (RegionalBusSourceException e) {
            log.warn("[RegionalBusCatalog] route listing failed, abort: {}", e.getMessage());
            return new RegionalBusCatalogLoadResult(0, 0, 0, 0, 0, true, false, elapsed(startedAt));
        }

        AtomicInteger loaded = new AtomicInteger();
        AtomicInteger empty = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicBoolean aborted = new AtomicBoolean(false);

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, properties.getConcurrency()));
        try {
            List<Future<?>> futures = new ArrayList<>(routes.size());
            for (TagoRoute route : routes) {
                futures.add(pool.submit(() -> {
                    if (aborted.get()) {
                        return;
                    }
                    try {
                        switch (loadRoute(route, startedAt)) {
                            case LOADED -> loaded.incrementAndGet();
                            case EMPTY -> empty.incrementAndGet();
                        }
                    } catch (RegionalBusSourceException e) {
                        failed.incrementAndGet();
                        if (e.blocking() && aborted.compareAndSet(false, true)) {
                            log.warn("[RegionalBusCatalog] blocking error, abort: {}", e.getMessage());
                        } else {
                            log.debug("[RegionalBusCatalog] route={} failed: {}", route.routeId(), e.getMessage());
                        }
                    } catch (RuntimeException e) {
                        failed.incrementAndGet();
                        log.warn("[RegionalBusCatalog] route={} store failed: {}", route.routeId(), e.getMessage());
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    aborted.set(true);
                    break;
                } catch (Exception ignored) {
                    // 작업 안에서 이미 셌다
                }
            }
        } finally {
            pool.shutdownNow();
        }

        int pruned = 0;
        if (!aborted.get() && !routes.isEmpty()) {
            Set<RegionalBusRouteStopStore.RouteKey> listed = new HashSet<>();
            routes.forEach(route -> listed.add(keyOf(route)));
            pruned = store.deleteRoutesNotIn(listed);
        }
        return new RegionalBusCatalogLoadResult(routes.size(), loaded.get(), empty.get(), failed.get(), pruned,
                aborted.get(), false, elapsed(startedAt));
    }

    private List<TagoRoute> listRoutes() {
        List<TagoRoute> routes = new ArrayList<>();
        for (TagoCity city : withRetry(source::tagoCities)) {
            if (city.cityCode() == null) {
                continue;
            }
            routes.addAll(withRetry(() -> source.tagoRoutes(city.cityCode())));
        }
        return routes.stream().filter(r -> r.routeId() != null).toList();
    }

    private enum Outcome { LOADED, EMPTY }

    /** 표에 들어가는 열쇠. 경기는 접두사를 뗀 9자리다 — 경기 실시간이 그 id 를 받는다. */
    static RegionalBusRouteStopStore.RouteKey keyOf(TagoRoute route) {
        return route.isGyeonggi()
                ? new RegionalBusRouteStopStore.RouteKey(RegionalBusProvider.GG, route.routeId().substring(3))
                : new RegionalBusRouteStopStore.RouteKey(RegionalBusProvider.TAGO, route.routeId());
    }

    private Outcome loadRoute(TagoRoute route, Instant loadedAt) {
        RegionalBusRouteStopStore.RouteKey key = keyOf(route);
        RegionalBusProvider provider = key.provider();
        String routeId = key.routeId();
        List<RegionalRouteStop> stops = withRetry(() -> route.isGyeonggi()
                ? source.gyeonggiRouteStops(routeId)
                : source.tagoRouteStops(route.cityCode(), routeId));
        List<RegionalRouteStop> valid = stops.stream()
                .filter(s -> s.stopId() != null && s.seq() > 0)
                .toList();
        if (valid.isEmpty()) {
            return Outcome.EMPTY;
        }
        store.replaceRoute(provider, routeId, route.routeNo(), route.cityCode(), valid, loadedAt);
        return Outcome.LOADED;
    }

    private <T> T withRetry(Supplier<T> call) {
        for (int attempt = 0; ; attempt++) {
            try {
                return call.get();
            } catch (RegionalBusSourceException e) {
                if (!e.retryable() || attempt >= properties.getMaxRetries()) {
                    throw e;
                }
                sleep(properties.getRetryBackoffMs() * (attempt + 1));
            }
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw RegionalBusSourceException.failed("interrupted");
        }
    }

    private Duration elapsed(Instant startedAt) {
        return Duration.between(startedAt, clock.instant());
    }
}
