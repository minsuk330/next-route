package watoo.grd.nextroute.application.arrival.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.arrival.config.BusStopArrivalProperties;
import watoo.grd.nextroute.application.arrival.dto.BusStopArrivalResponse;
import watoo.grd.nextroute.application.arrival.dto.StopArrivals;
import watoo.grd.nextroute.application.arrival.dto.StopArrivals.Status;
import watoo.grd.nextroute.application.arrival.port.in.GetBusStopArrivalsUseCase;
import watoo.grd.nextroute.application.arrival.port.out.BusStopArrivalCachePort;
import watoo.grd.nextroute.application.arrival.port.out.BusStopCatalogPort;
import watoo.grd.nextroute.application.arrival.port.out.GyeonggiStopArrivalPort;
import watoo.grd.nextroute.application.arrival.port.out.SeoulStationArrivalPort;
import watoo.grd.nextroute.application.arrival.port.out.TagoStopArrivalPort;
import watoo.grd.nextroute.application.route.concurrent.SingleFlight;
import watoo.grd.nextroute.application.route.mapping.TagoCityCodes;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.Outcome;

/**
 * 정류장 단위 버스 도착(#577). 공급자는 정류장마다 한 번만 부른다 — 정류장 단위 원본 API 가 노선을 한 번에 다 준다.
 *
 * <ul>
 *   <li>9자리 숫자 id 는 서울과 경기가 <b>같이</b> 쓴다(같은 실제 정류장, 예 {@code 121000009} = 서울 20노선 + 경기
 *       35노선). 카탈로그에 있는 공급자를 모두 부르고 줄을 합친다.</li>
 *   <li>TAGO id(영문 3글자 + 숫자)는 정류장의 도시코드마다 부른다(정류장 하나가 도시 여럿일 수 있다).</li>
 *   <li>어느 카탈로그에도 없는 9자리 id 는 공급자를 부르지 않는다 — 임의 id 로 한도를 태우지 못하게. TAGO 는 카탈로그에
 *       없으면 접두사 표로 도시를 찾되, 정류장 단위 TAGO 일일 몫이 상한이다.</li>
 *   <li>공급자 결과는 정류장 단위로 캐시한다(성공한 빈 결과 포함). 같은 키의 동시 조회는 하나로 묶는다(JVM 안. 이 경로는
 *       api 한 대가 받는다).</li>
 *   <li>캐시는 요청 스레드에서 먼저 읽는다 — 다 맞으면 실행기를 쓰지 않는다. 놓친 것만 기한을 두고 병렬로 부른다. 늦거나
 *       실행기가 꽉 찬 공급자는 그 정류장의 실패로 두고 나머지를 돌려준다.</li>
 *   <li>카탈로그(DB) 조회가 실패한 정류장은 ERROR 다(요청 전체를 500 으로 만들지 않는다).</li>
 * </ul>
 *
 * <p>트랜잭션을 걸지 않는다 — 외부 호출을 기다리는 동안 DB 커넥션을 잡지 않게.
 */
@Slf4j
@Service
public class BusStopArrivalQueryService implements GetBusStopArrivalsUseCase {

    static final String SOURCE_SEOUL = BusRouteTypeNames.SOURCE_SEOUL;
    static final String SOURCE_GYEONGGI = BusRouteTypeNames.SOURCE_GYEONGGI;
    static final String SOURCE_TAGO = BusRouteTypeNames.SOURCE_TAGO;

    private static final Pattern TAGO_ID = Pattern.compile("^[A-Z]{3}[\\dA-Z-]+$");
    private static final Pattern NINE_DIGITS = Pattern.compile("^\\d{9}$");

    private final BusStopCatalogPort catalog;
    private final SeoulStationArrivalPort seoul;
    private final GyeonggiStopArrivalPort gyeonggi;
    private final TagoStopArrivalPort tago;
    private final BusStopArrivalCachePort cache;
    private final AsyncTaskExecutor executor;
    private final BusStopArrivalProperties props;
    private final SingleFlight<String, Fetched> flight = new SingleFlight<>();

    public BusStopArrivalQueryService(BusStopCatalogPort catalog,
                                      SeoulStationArrivalPort seoul,
                                      GyeonggiStopArrivalPort gyeonggi,
                                      TagoStopArrivalPort tago,
                                      BusStopArrivalCachePort cache,
                                      @Qualifier("busStopArrivalExecutor") AsyncTaskExecutor executor,
                                      BusStopArrivalProperties props) {
        this.catalog = catalog;
        this.seoul = seoul;
        this.gyeonggi = gyeonggi;
        this.tago = tago;
        this.cache = cache;
        this.executor = executor;
        this.props = props;
    }

    /**
     * 공급자 하나에 정류장 하나. {@code key} 는 캐시와 묶기 키, {@code callId} 는 공급자 호출 키다. 서울은 arsId 로 부르지만
     * 답을 stId 로 걸러 저장하므로 키에 stId 를 붙인다(같은 arsId 를 쓰는 다른 stId 가 남의 줄을 받지 않게).
     */
    record Target(String source, String key, String stopId, String callId, String cityCode) {
    }

    /** 한 공급자 조회의 결과. 실패면 rows 는 비어 있다. */
    record Fetched(Outcome outcome, List<BusStopArrivalResponse> rows) {
        static Fetched failed(Outcome outcome) {
            return new Fetched(outcome, List.of());
        }
    }

    @Override
    public List<BusStopArrivalResponse> getStopArrivals(String stopId) {
        List<StopArrivals> result = getStopsArrivals(stopId == null ? List.of() : List.of(stopId));
        return result.isEmpty() ? List.of() : result.get(0).arrivals();
    }

    @Override
    public List<StopArrivals> getStopsArrivals(List<String> stopIds) {
        List<String> ids = normalize(stopIds);
        if (ids.size() > MAX_STOPS) {
            throw new IllegalArgumentException("stopIds 는 최대 " + MAX_STOPS + "개");
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(props.getDeadlineMs());

        Map<String, List<Target>> targetsByStop = new java.util.LinkedHashMap<>();
        Map<Target, CompletableFuture<Fetched>> futures = new java.util.LinkedHashMap<>();
        for (String id : ids) {
            List<Target> targets;
            try {
                targets = targets(id);
            } catch (RuntimeException e) {
                log.warn("[BusStopArrival] catalog lookup failed stopId={}: {}", id, e.toString());
                targetsByStop.put(id, null);
                continue;
            }
            targetsByStop.put(id, targets);
            for (Target target : targets) {
                futures.computeIfAbsent(target, t -> cached(t).orElseGet(() -> submit(t, deadlineNanos)));
            }
        }

        List<StopArrivals> out = new ArrayList<>();
        for (String id : ids) {
            List<Target> targets = targetsByStop.get(id);
            if (targets == null) {
                out.add(new StopArrivals(id, Status.ERROR, List.of()));
                continue;
            }
            if (targets.isEmpty()) {
                out.add(new StopArrivals(id, Status.UNKNOWN_STOP, List.of()));
                continue;
            }
            List<List<BusStopArrivalResponse>> rows = new ArrayList<>();
            List<Outcome> outcomes = new ArrayList<>();
            for (Target target : targets) {
                Fetched fetched = await(futures.get(target), deadlineNanos);
                outcomes.add(fetched.outcome());
                if (fetched.outcome() == Outcome.OK) {
                    rows.add(fetched.rows());
                }
            }
            out.add(new StopArrivals(id, status(outcomes), BusStopArrivalMappers.mergeSorted(rows)));
        }
        return List.copyOf(out);
    }

    /** 공백을 지우고, 빈 값과 중복을 빼고, 요청 순서를 지킨다. */
    static List<String> normalize(List<String> stopIds) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (stopIds != null) {
            for (String id : stopIds) {
                if (id != null && !id.isBlank()) {
                    ids.add(id.trim());
                }
            }
        }
        return List.copyOf(ids);
    }

    /** 이 정류장을 위해 부를 공급자들. 비면 모르는 정류장. */
    List<Target> targets(String stopId) {
        List<Target> targets = new ArrayList<>();
        if (TAGO_ID.matcher(stopId).matches()) {
            List<String> cities = catalog.tagoCityCodes(stopId);
            if (cities.isEmpty()) {
                cities = TagoCityCodes.cityCodeOfId(stopId).map(List::of).orElse(List.of());
            }
            for (String city : cities) {
                targets.add(new Target(SOURCE_TAGO, city + ":" + stopId, stopId, stopId, city));
            }
            return targets;
        }
        if (!NINE_DIGITS.matcher(stopId).matches()) {
            return targets;
        }
        catalog.seoulArsId(stopId).ifPresent(ars ->
                targets.add(new Target(SOURCE_SEOUL, ars + ":" + stopId, stopId, ars, null)));
        if (catalog.isGyeonggiStop(stopId)) {
            targets.add(new Target(SOURCE_GYEONGGI, stopId, stopId, stopId, null));
        }
        return targets;
    }

    /** 요청 스레드에서 읽는 캐시. Redis 장애는 miss 다(어댑터가 삼킨다). */
    private Optional<CompletableFuture<Fetched>> cached(Target target) {
        return cache.get(target.source(), target.key())
                .map(rows -> CompletableFuture.completedFuture(new Fetched(Outcome.OK, rows)));
    }

    private CompletableFuture<Fetched> submit(Target target, long deadlineNanos) {
        try {
            return CompletableFuture.supplyAsync(() -> fetch(target, deadlineNanos), executor);
        } catch (RejectedExecutionException e) {
            log.debug("[BusStopArrival] executor full; skip {}", target);
            return CompletableFuture.completedFuture(Fetched.failed(Outcome.LIMITED));
        }
    }

    private Fetched await(CompletableFuture<Fetched> future, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        try {
            return remaining <= 0 && !future.isDone()
                    ? Fetched.failed(Outcome.LIMITED)
                    : future.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return Fetched.failed(Outcome.LIMITED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Fetched.failed(Outcome.ERROR);
        } catch (ExecutionException e) {
            log.warn("[BusStopArrival] fetch failed: {}", e.getCause() == null ? e : e.getCause().toString());
            return Fetched.failed(Outcome.ERROR);
        }
    }

    /** 같은 키 묶기 → 공급자. 성공(빈 결과 포함)만 캐시한다. 캐시는 요청 스레드가 이미 봤다. */
    Fetched fetch(Target target, long deadlineNanos) {
        long joinWaitMs = Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
        SingleFlight.Outcome<Fetched> outcome = flight.execute(target.source() + ":" + target.key(),
                () -> load(target, deadlineNanos), joinWaitMs);
        return outcome.hasValue() ? outcome.value() : Fetched.failed(Outcome.LIMITED);
    }

    private Fetched load(Target target, long deadlineNanos) {
        Fetched fetched = switch (target.source()) {
            case SOURCE_SEOUL -> {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                if (remainingMs <= 0) {
                    yield Fetched.failed(Outcome.LIMITED);
                }
                yield fromResult(seoul.getStationArrivals(target.callId(), remainingMs),
                        rows -> seoulRows(rows, target));
            }
            case SOURCE_GYEONGGI -> fromResult(gyeonggi.getStationArrivals(target.callId()), BusStopArrivalMappers::gyeonggi);
            case SOURCE_TAGO -> fromResult(tago.getStopArrivals(target.cityCode(), target.callId()), BusStopArrivalMappers::tago);
            default -> Fetched.failed(Outcome.ERROR);
        };
        if (fetched.outcome() == Outcome.OK) {
            cache.put(target.source(), target.key(), fetched.rows());
        }
        return fetched;
    }

    /** 원본에 줄이 있는데 이 stId 줄이 하나도 없으면 우리 {@code bus_stop.ars_id} 가 낡은 것이다(정적 적재 OFF). */
    private static List<BusStopArrivalResponse> seoulRows(List<watoo.grd.nextroute.application.arrival.dto.SeoulStationArrival> rows,
                                                         Target target) {
        List<BusStopArrivalResponse> mapped = BusStopArrivalMappers.seoul(rows, target.stopId());
        if (mapped.isEmpty() && rows != null && !rows.isEmpty()) {
            log.warn("[BusStopArrival] Seoul arsId={} has {} rows but none for stId={} (stale ars_id?)",
                    target.callId(), rows.size(), target.stopId());
        }
        return mapped;
    }

    private static <T> Fetched fromResult(BusQueryResult<T> result,
                                          java.util.function.Function<List<T>, List<BusStopArrivalResponse>> mapper) {
        if (result == null) {
            return Fetched.failed(Outcome.ERROR);
        }
        return result.isOk() ? new Fetched(Outcome.OK, mapper.apply(result.data())) : Fetched.failed(result.outcome());
    }

    /** 하나라도 OK 면 OK. 모두 실패면 차단 > 제한 > 오류 순으로 알린다(차단이 가장 오래간다). */
    static Status status(List<Outcome> outcomes) {
        if (outcomes.contains(Outcome.OK)) {
            return Status.OK;
        }
        if (outcomes.contains(Outcome.BLOCKED)) {
            return Status.BLOCKED;
        }
        if (outcomes.contains(Outcome.LIMITED)) {
            return Status.LIMITED;
        }
        return Status.ERROR;
    }
}
