package watoo.grd.nextroute.application.subway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.subway.dto.DirectedRailPattern;
import watoo.grd.nextroute.application.subway.dto.OtpRailRoute;
import watoo.grd.nextroute.application.subway.port.out.SubwayRoutePatternPort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * line_id·실시간 방향의 OTP 패턴을 하루 단위로 캐시해 준다(#283).
 *
 * <p>패턴은 그래프를 다시 빌드할 때만 바뀐다. 그런데 한 번 받는 데 노선당 1~2.5초·최대 1.4MB 라
 * 요청마다 부르면 그 시간이 곧 화면 대기다.
 *
 * <p><b>한 번 적재에 예산 {@link #LOAD_BUDGET}.</b> 노선 목록과 그 방향의 노선들(2호선 내선이면 3개)을
 * 이 안에서 다 받아야 한다. 남은 예산을 호출마다 응답 대기 한계로 넘긴다 — 노선마다 고정 대기를 주면
 * OTP 가 느린 날 첫 호출이 수십 초가 되고, 앱은 열차 안에서 그만큼 기다린다(#291).
 *
 * <p><b>새로 고치다 실패하면 낡은 값을 그대로 준다.</b> 하루 지난 역 순서는 거의 언제나 맞지만,
 * 빈 목록은 "남은 역 없음(종착)"과 구분되지 않는다. 낡은 값도 없을 때만 예외를 올린다.
 */
@Slf4j
@Service
public class SubwayLinePatternService {

    static final Duration TTL = Duration.ofHours(24);
    static final Duration LOAD_BUDGET = Duration.ofSeconds(5);

    private final SubwayRoutePatternPort port;
    private final Clock clock;

    private volatile Cached<List<OtpRailRoute>> routes;
    private final Map<String, Cached<List<DirectedRailPattern>>> patterns = new ConcurrentHashMap<>();

    /** {@link #loadLater} 의 진행 중 키. 같은 키 적재가 겹치면 OTP 를 두 번 부른다. */
    private final Set<String> loading = ConcurrentHashMap.newKeySet();
    private final ExecutorService loader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "subway-pattern-load-later");
        thread.setDaemon(true);
        return thread;
    });

    public SubwayLinePatternService(SubwayRoutePatternPort port, Clock clock) {
        this.port = port;
        this.clock = clock;
    }

    /**
     * @return 그 방향 패턴들(본선+지선). 그 노선에 그 방향이 없으면 빈 목록
     * @throws RuntimeException OTP 호출이 실패했고 낡은 값도 없을 때
     */
    public List<DirectedRailPattern> patterns(String lineId, String direction) {
        String key = lineId + ":" + direction;
        Cached<List<DirectedRailPattern>> cached = patterns.get(key);
        if (cached != null && cached.isFresh(clock.instant())) {
            return cached.value();
        }
        try {
            List<DirectedRailPattern> loaded = load(lineId, direction);
            patterns.put(key, new Cached<>(loaded, clock.instant()));
            return loaded;
        } catch (RuntimeException e) {
            if (cached == null) {
                throw e;
            }
            log.warn("[OTP] 지하철 패턴 갱신 실패 — 낡은 값을 쓴다 line={} direction={} loadedAt={}: {}",
                    lineId, direction, cached.loadedAt(), e.getMessage());
            return cached.value();
        }
    }

    /**
     * <b>이미 적재된</b> 패턴만(낡았어도) 준다. 없으면 빈 목록 — OTP 를 부르지 않는다(#298 근처 역 GTFS id 처럼 응답 지연에
     * 적재를 넣으면 안 되는 곳용). 채우는 것은 워밍업·{@link #patterns} 다.
     */
    public List<DirectedRailPattern> cachedPatterns(String lineId, String direction) {
        Cached<List<DirectedRailPattern>> cached = patterns.get(lineId + ":" + direction);
        return cached == null ? List.of() : cached.value();
    }

    /**
     * <b>이미 적재된</b> 지하철 노선 목록(낡았어도). 없으면 빈 목록 — OTP 를 부르지 않는다. 급행 노선 id 가 그래프에
     * 아직 있는지 대조하는 데 쓴다(subway-express S2, 그래프 재빌드 때 id 가 바뀔 수 있다).
     */
    public List<OtpRailRoute> cachedRoutes() {
        Cached<List<OtpRailRoute>> cached = routes;
        return cached == null ? List.of() : cached.value();
    }

    /**
     * 뒤에서 한 번 적재한다(#317). {@link #cachedPatterns} 가 비어 응답이 노선도를 비워 보낸 뒤 다음 요청을 채우려고 쓴다.
     * 같은 키가 이미 적재 중이면 아무것도 안 한다. 실패는 로그만.
     */
    public void loadLater(String lineId, String direction) {
        String key = lineId + ":" + direction;
        if (!loading.add(key)) {
            return;
        }
        try {
            loader.execute(() -> {
                try {
                    patterns(lineId, direction);
                } catch (RuntimeException e) {
                    log.warn("[OTP] 지하철 패턴 지연 적재 실패 line={} direction={}: {}", lineId, direction, e.getMessage());
                } finally {
                    loading.remove(key);
                }
            });
        } catch (RuntimeException e) {
            loading.remove(key);
            throw e;
        }
    }

    private List<DirectedRailPattern> load(String lineId, String direction) {
        Instant deadline = clock.instant().plus(LOAD_BUDGET);
        List<DirectedRailPattern> directed = new ArrayList<>();
        for (OtpRailRoute route : SubwayRoutePatternAssembler.selectRoutes(routes(deadline), lineId, direction)) {
            directed.addAll(SubwayRoutePatternAssembler.orient(
                    route, port.patterns(route.gtfsId(), remaining(deadline)), direction));
        }
        return List.copyOf(directed);
    }

    /** 남은 예산. 다 썼으면 부르지 않고 실패한다 — 0 을 넘기면 HTTP 클라이언트는 "무한 대기"로 읽는다. */
    private Duration remaining(Instant deadline) {
        Duration left = Duration.between(clock.instant(), deadline);
        if (left.isNegative() || left.isZero()) {
            throw new IllegalStateException("지하철 패턴 적재 예산(" + LOAD_BUDGET.toSeconds() + "초) 초과");
        }
        return left;
    }

    private List<OtpRailRoute> routes(Instant deadline) {
        Cached<List<OtpRailRoute>> cached = routes;
        if (cached != null && cached.isFresh(clock.instant())) {
            return cached.value();
        }
        try {
            List<OtpRailRoute> loaded = List.copyOf(port.subwayRoutes(remaining(deadline)));
            routes = new Cached<>(loaded, clock.instant());
            return loaded;
        } catch (RuntimeException e) {
            if (cached == null) {
                throw e;
            }
            log.warn("[OTP] 지하철 노선 목록 갱신 실패 — 낡은 값을 쓴다: {}", e.getMessage());
            return cached.value();
        }
    }

    private record Cached<T>(T value, Instant loadedAt) {
        boolean isFresh(Instant now) {
            return loadedAt.plus(TTL).isAfter(now);
        }
    }
}
