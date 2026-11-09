package watoo.grd.nextroute.application.arrival.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteStopStore;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.entity.BusStop;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 다가오는 버스가 지금 있는 정류장부터 탑승 정류장 바로 앞까지의 이름(bus-boarding-flow S1). 앱의 정거장 띠가 쓴다.
 *
 * <p>버스는 순번 {@code 탑승 순번 − 몇 정류장 전} 에 있다고 본다. 서울은 2026-11-10 실응답 10건에서 그 순번의 이름이 공급자가 준
 * "현재 정류소명"과 전부 같았다. 그래도 요청마다 다시 맞춰 본다 — 공급자가 준 현재 정류장 이름과 첫 이름이 다르면 목록을 주지
 * 않는다. 경기는 순번 세는 법(경유 정류장 포함 여부)을 실측하지 못해, 이름이 맞을 때만 준다.
 *
 * <p>이름을 못 구하면 null 이다. 일부만 아는 목록은 주지 않는다(띠의 칸이 실제 정류장과 어긋난다). 도착 정보 자체는 그대로 나가야
 * 하므로 예외를 밖으로 내지 않고, <b>부른 쪽 트랜잭션 밖에서</b> 읽는다 — 안에서 읽으면 조회 실패가 그 트랜잭션을 rollback-only 로
 * 만들어, 예외를 삼켜도 커밋에서 도착 응답이 500 이 된다.
 *
 * <p>노선의 정류장 표는 거의 바뀌지 않아 노선별로 10분 기억한다(도착 조회는 화면마다 15~30초에 한 번 온다). 밤사이 표가 다시
 * 적재되면 최대 10분은 옛 이름이 나갈 수 있다 — 그때는 현재 정류장 이름 대조가 어긋난 목록을 걸러 준다.
 */
@Slf4j
@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class BusApproachStopNames {

    /** 띠에 그릴 수 있는 이름 수. 넘으면 처음 1개 + 마지막 2개. */
    static final int MAX_NAMES = 3;
    private static final Duration TTL = Duration.ofMinutes(10);
    static final int MAX_ROUTES = 500;
    private static final Duration LOAD_WAIT = Duration.ofSeconds(3);

    private final BusRouteStopRepository routeStopRepository;
    private final BusStopRepository stopRepository;
    private final RegionalBusRouteStopStore regionalStore;
    private final Clock clock;
    private final Map<String, CachedRoute> cache = new ConcurrentHashMap<>();
    /** 지금 읽고 있는 노선. 같은 노선을 기다리는 요청은 그 읽기 하나의 결과를 받는다. */
    private final Map<String, CompletableFuture<CachedRoute>> loading = new ConcurrentHashMap<>();
    /** 공급자 이름과 표 이름의 공백 차이(전각 공백·줄바꿈 없는 공백 포함)를 지운다. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /** 한 노선의 순번 → 이름, 그리고 노선에 한 번만 나오는 정류장 id → 순번(경기용). */
    private record CachedRoute(Map<Integer, String> nameBySeq, Map<String, Integer> uniqueSeqByStopId, Instant expiresAt) {
    }

    public BusApproachStopNames(BusRouteStopRepository routeStopRepository, BusStopRepository stopRepository,
                                RegionalBusRouteStopStore regionalStore, Clock clock) {
        this.routeStopRepository = routeStopRepository;
        this.stopRepository = stopRepository;
        this.regionalStore = regionalStore;
        this.clock = clock;
    }

    /**
     * 서울: 탑승 정류장 순번을 이미 안다(도착 조회가 그 순번으로 불렀다).
     *
     * @param busStopName 공급자가 준 버스의 현재 정류장 이름. 있으면 첫 이름과 맞춰 보고, 없으면 대조 없이 준다
     */
    public List<String> seoul(String routeId, int boardingSeq, Integer stopsAway, String busStopName) {
        if (stopsAway == null || stopsAway < 1) {
            return null;
        }
        try {
            List<String> names = names(route("SEOUL:" + routeId, () -> loadSeoul(routeId)), boardingSeq, stopsAway);
            return names != null && (isBlank(busStopName) || sameName(names.get(0), busStopName)) ? names : null;
        } catch (RuntimeException e) {
            log.debug("[ApproachStops] seoul lookup failed routeId={}: {}", routeId, e.toString());
            return null;
        }
    }

    /**
     * 경기: 탑승 정류장 순번을 표에서 찾는다. 같은 노선에 그 정류장이 두 번이면 고르지 않는다(반대 방향일 수 있다).
     * 공급자가 준 현재 정류장 이름이 첫 이름과 같을 때만 준다 — 이름을 안 주면 순번이 맞는지 알 길이 없어 주지 않는다.
     */
    public List<String> gyeonggi(String routeId, String stopId, Integer stopsAway, String busStopName) {
        if (stopsAway == null || stopsAway < 1 || stopId == null || isBlank(busStopName)) {
            return null;
        }
        try {
            CachedRoute route = route("GG:" + routeId, () -> loadRegional(RegionalBusProvider.GG, routeId));
            Integer boardingSeq = route.uniqueSeqByStopId().get(stopId);
            if (boardingSeq == null) {
                return null;
            }
            List<String> names = names(route, boardingSeq, stopsAway);
            return names != null && sameName(names.get(0), busStopName) ? names : null;
        } catch (RuntimeException e) {
            log.debug("[ApproachStops] gyeonggi lookup failed routeId={}: {}", routeId, e.toString());
            return null;
        }
    }

    /**
     * 띠에 올릴 순번. 버스는 {@code boardingSeq - stopsAway} 에 있고 탑승 정류장 바로 앞은 {@code boardingSeq - 1}.
     * 3개 이하면 전부, 넘으면 버스가 있는 곳 1개 + 탑승 정류장 앞 2개.
     */
    static List<Integer> pickSeqs(int boardingSeq, int stopsAway) {
        int first = boardingSeq - stopsAway;
        List<Integer> seqs = new ArrayList<>();
        if (stopsAway <= MAX_NAMES) {
            for (int seq = first; seq < boardingSeq; seq++) {
                seqs.add(seq);
            }
        } else {
            seqs.add(first);
            seqs.add(boardingSeq - 2);
            seqs.add(boardingSeq - 1);
        }
        return seqs;
    }

    private static List<String> names(CachedRoute route, int boardingSeq, int stopsAway) {
        List<String> names = new ArrayList<>();
        for (int seq : pickSeqs(boardingSeq, stopsAway)) {
            String name = route.nameBySeq().get(seq);
            if (isBlank(name)) {
                return null;
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    /** 공백 차이는 같은 이름으로 본다(공급자 응답과 표의 띄어쓰기가 다를 수 있다). */
    static boolean sameName(String a, String b) {
        return a != null && b != null
                && WHITESPACE.matcher(a).replaceAll("").equals(WHITESPACE.matcher(b).replaceAll(""));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 노선 하나를 읽는 일은 키마다 한 번에 하나만 돈다 — 만료 순간에 그 노선을 보던 화면들이 한꺼번에 표를 읽지 않게.
     * 읽기는 맵의 잠금 밖에서 한다(잠금을 쥔 채 DB 를 기다리면 다른 노선의 요청까지 선다). 실패한 읽기는 기억하지 않는다 —
     * 기다리던 요청도 같은 예외를 받고, 다음 요청이 다시 읽는다.
     */
    private CachedRoute route(String key, Supplier<CachedRoute> loader) {
        Instant now = clock.instant();
        CachedRoute cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached;
        }
        CompletableFuture<CachedRoute> mine = new CompletableFuture<>();
        CompletableFuture<CachedRoute> running = loading.putIfAbsent(key, mine);
        if (running != null) {
            return await(running);
        }
        try {
            // 내가 캐시를 본 뒤, 읽던 요청이 값을 넣고 자리를 비웠을 수 있다. 다시 읽지 않는다.
            CachedRoute justLoaded = cache.get(key);
            if (justLoaded != null && justLoaded.expiresAt().isAfter(now)) {
                mine.complete(justLoaded);
                return justLoaded;
            }
            CachedRoute loaded = loader.get();
            if (cache.size() >= MAX_ROUTES) {
                cache.values().removeIf(route -> !route.expiresAt().isAfter(now));
                if (cache.size() >= MAX_ROUTES) {
                    cache.clear();   // 10분 안에 500 노선을 넘게 본 드문 경우. 비우고 다시 채운다.
                }
            }
            cache.put(key, loaded);
            mine.complete(loaded);
            return loaded;
        } catch (Throwable t) {
            // Error 까지 잡는다 — 완료되지 않은 채 자리만 비우면 기다리던 요청이 영영 선다.
            mine.completeExceptionally(t);
            throw t;
        } finally {
            loading.remove(key, mine);
        }
    }

    /** 남이 읽는 것을 기다리되 끝없이 기다리지는 않는다(조회가 걸려도 도착 응답은 나가야 한다). */
    private static CachedRoute await(CompletableFuture<CachedRoute> running) {
        try {
            return running.get(LOAD_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a route load", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("route load did not finish", e);
        }
    }

    private CachedRoute loadSeoul(String routeId) {
        List<BusRouteStop> stops = routeStopRepository.findByRouteIdOrderBySeq(routeId);
        Map<String, String> nameByStopId = stopRepository
                .findByStopIdIn(stops.stream().map(BusRouteStop::getStopId).filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream()
                .filter(stop -> stop.getStopName() != null)
                .collect(Collectors.toMap(BusStop::getStopId, BusStop::getStopName, (a, b) -> a));
        Map<Integer, String> nameBySeq = new HashMap<>();
        for (BusRouteStop stop : stops) {
            String name = stop.getStopId() == null ? null : nameByStopId.get(stop.getStopId());
            if (stop.getSeq() != null && name != null) {
                nameBySeq.put(stop.getSeq(), name);
            }
        }
        return new CachedRoute(Map.copyOf(nameBySeq), Map.of(), clock.instant().plus(TTL));
    }

    private CachedRoute loadRegional(RegionalBusProvider provider, String routeId) {
        Map<Integer, String> nameBySeq = new HashMap<>();
        Map<String, Integer> seqByStopId = new HashMap<>();
        Set<String> repeated = new HashSet<>();
        for (RegionalRouteStop stop : regionalStore.findRouteStops(provider, routeId)) {
            if (stop.stopName() != null) {
                nameBySeq.put(stop.seq(), stop.stopName());
            }
            if (stop.stopId() != null && seqByStopId.put(stop.stopId(), stop.seq()) != null) {
                repeated.add(stop.stopId());
            }
        }
        repeated.forEach(seqByStopId::remove);
        return new CachedRoute(Map.copyOf(nameBySeq), Map.copyOf(seqByStopId), clock.instant().plus(TTL));
    }
}
