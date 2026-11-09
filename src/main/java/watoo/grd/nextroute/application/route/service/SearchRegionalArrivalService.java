package watoo.grd.nextroute.application.route.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.arrival.dto.TagoBusArrival;
import watoo.grd.nextroute.application.arrival.port.out.GyeonggiBusArrivalPort;
import watoo.grd.nextroute.application.arrival.port.out.TagoBusArrivalPort;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.mapping.TagoCityCodeLookup;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 경로 검색이 경기(GBIS)·TAGO 버스 도착을 묻는 길(search-regional-bus-wait PR-2). 여정용 {@code RegionalArrivalLookup} 과
 * 몫·동시성·기한을 나눈다.
 *
 * <ul>
 *   <li>몫: 검색 전용 일일 버킷({@code search-gg-arrival}, {@code search-tago-arrival})을 호출 전에 센다. 경기는 어댑터가 공개 몫
 *       ({@code gg-arrival})도 캐시 miss 때만 센다 — 검색이 공개 몫을 다 쓰지 못하게 위에 검색 상한을 하나 더 둔 것이다.</li>
 *   <li>동시성: 공급자별 검색 전용 풀(기본 3스레드, 큐 4배). 넘치는 조회는 큐에서 기다리고 큐가 차면 LIMITED — TAGO 서버 합산
 *       8자리를 검색이 독점하지 못하게.</li>
 *   <li>기한: 크기 제한 전용 풀에서 띄우고 부르는 쪽이 남은 시간만큼만 기다린다. 넘으면 포기(호출은 끝까지 돌아 공유 캐시를 채운다).</li>
 * </ul>
 */
@Slf4j
@Component
public class SearchRegionalArrivalService {

    /** 경기 GBIS 노선 id(2로 시작하는 9자리). 서울은 1로 시작해 겹치지 않는다({@code TransferStopResolver} 와 같은 규칙). */
    private static final Pattern GBIS_ROUTE_ID = Pattern.compile("^2\\d{8}$");
    /** TAGO 노선 id(영문 3글자 접두사). {@code RegionalArrivalLookup} 과 같은 규칙. */
    private static final Pattern TAGO_ROUTE_ID = Pattern.compile("^[A-Z]{3}[\\dA-Z-]+$");

    public enum Provider { GG, TAGO }

    public enum Outcome { OK, LIMITED, ERROR, TIMEOUT }

    /** OK 면 {@code arrivals} = 지금부터 도착까지 남은 시간들(fetchedAt 기준), 그 밖에는 빈 목록. */
    public record Result(Outcome outcome, Instant fetchedAt, List<Integer> secondsAhead) {
        static Result of(Outcome o) {
            return new Result(o, null, List.of());
        }

        /** {@code userAt} 이후 처음 오는 버스. 응답은 다음 한두 대뿐이라 없으면 비어 있다(놓쳤다고 단정하지 않는다). */
        public Optional<Instant> boardableAt(Instant userAt) {
            if (outcome != Outcome.OK) {
                return Optional.empty();
            }
            return secondsAhead.stream()
                    .filter(s -> s != null && s >= 0)
                    .map(s -> fetchedAt.plusSeconds(s))
                    .filter(at -> !at.isBefore(userAt))
                    .min(Instant::compareTo);
        }
    }

    public record Pending(Provider provider, CompletableFuture<Result> future) {
        public Result await(long timeoutMs) {
            try {
                return future.get(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                return Result.of(Outcome.TIMEOUT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.of(Outcome.ERROR);
            } catch (ExecutionException e) {
                return Result.of(Outcome.ERROR);
            }
        }
    }

    private final GyeonggiBusArrivalPort gyeonggiPort;
    private final TagoBusArrivalPort tagoPort;
    private final TagoCityCodeLookup cityCodes;
    private final BusApiQuotaPort quota;
    private final TransferArrivalProperties props;
    private final RouteSearchMetrics metrics;
    private final Clock clock;
    private final ThreadPoolExecutor ggPool;
    private final ThreadPoolExecutor tagoPool;

    public SearchRegionalArrivalService(GyeonggiBusArrivalPort gyeonggiPort, TagoBusArrivalPort tagoPort,
                                        TagoCityCodeLookup cityCodes, BusApiQuotaPort quota,
                                        TransferArrivalProperties props, RouteSearchMetrics metrics, Clock clock) {
        this.gyeonggiPort = gyeonggiPort;
        this.tagoPort = tagoPort;
        this.cityCodes = cityCodes;
        this.quota = quota;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
        int threads = Math.max(1, props.getRegionalMaxConcurrent());
        this.ggPool = pool("search-regional-gg", threads);
        this.tagoPool = pool("search-regional-tago", threads);
    }

    private static ThreadPoolExecutor pool(String name, int threads) {
        return new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(threads * 4),
                r -> {
                    Thread t = new Thread(r, name);
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        ggPool.shutdownNow();
        tagoPool.shutdownNow();
    }

    /** 경기·TAGO 로 물을 구간의 공급자. 둘 다 아니거나 정류장 id 가 없으면 빈값. */
    public static Optional<Provider> providerOf(String routeId, String stopId) {
        if (routeId == null || stopId == null || stopId.isBlank()) {
            return Optional.empty();
        }
        if (GBIS_ROUTE_ID.matcher(routeId).matches()) {
            return Optional.of(Provider.GG);
        }
        return TAGO_ROUTE_ID.matcher(routeId).matches() ? Optional.of(Provider.TAGO) : Optional.empty();
    }

    public boolean enabled() {
        return props.isRegionalEnabled();
    }

    /** 조회를 띄운다. 몫이 없거나 풀 큐가 차면 곧바로 LIMITED 로 끝난 Pending. */
    public Pending start(Provider provider, String routeId, String stopId) {
        boolean admitted = provider == Provider.GG
                ? quota.tryAcquire("search-gg-arrival", props.getRegionalGyeonggiDailyLimit())
                : quota.tryAcquire("search-tago-arrival", props.getRegionalTagoDailyLimit());
        if (!admitted) {
            return done(provider, Outcome.LIMITED, "quota");
        }
        try {
            CompletableFuture<Result> f = CompletableFuture.supplyAsync(() -> fetch(provider, routeId, stopId),
                    provider == Provider.GG ? ggPool : tagoPool);
            return new Pending(provider, f.whenComplete((r, e) ->
                    metrics.recordRegionalWait(provider.name(), r == null ? "error" : r.outcome().name().toLowerCase())));
        } catch (RejectedExecutionException e) {
            return done(provider, Outcome.LIMITED, "pool");
        }
    }

    /** 부르는 쪽이 기한 안에 결과를 못 받았다(호출 자체는 끝나면 ok/error 로 따로 센다). */
    public void recordAbandoned(Provider provider) {
        metrics.recordRegionalWait(provider.name(), "abandoned");
    }

    private Pending done(Provider provider, Outcome outcome, String why) {
        metrics.recordRegionalWait(provider.name(), "limited_" + why);
        return new Pending(provider, CompletableFuture.completedFuture(Result.of(outcome)));
    }

    private Result fetch(Provider provider, String routeId, String stopId) {
        try {
            if (provider == Provider.GG) {
                BusQueryResult<GyeonggiBusArrival> r = gyeonggiPort.getArrival(stopId, routeId, true);
                if (r.isOk()) {
                    List<Integer> s = new ArrayList<>();
                    for (GyeonggiBusArrival a : r.data()) {
                        if (a.predictMin1() != null) s.add(a.predictMin1() * 60);
                        if (a.predictMin2() != null) s.add(a.predictMin2() * 60);
                    }
                    return new Result(Outcome.OK, clock.instant(), s);
                }
                return Result.of(r.outcome() == watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort
                        .Outcome.LIMITED ? Outcome.LIMITED : Outcome.ERROR);
            }
            Optional<String> city = cityCodes.cityCodeOfRoute(routeId);
            if (city.isEmpty()) {
                return Result.of(Outcome.ERROR);
            }
            BusQueryResult<TagoBusArrival> r = tagoPort.getArrivals(city.get(), stopId, routeId);
            if (r.isOk()) {
                List<Integer> s = new ArrayList<>();
                for (TagoBusArrival a : r.data()) {
                    if (a.arrSeconds() != null) s.add(a.arrSeconds());
                }
                return new Result(Outcome.OK, clock.instant(), s);
            }
            return Result.of(Outcome.ERROR);
        } catch (RuntimeException e) {
            log.warn("[SearchRegional] {} 도착 조회 실패 route={} stop={}: {}", provider, routeId, stopId, e.toString());
            return Result.of(Outcome.ERROR);
        }
    }
}
