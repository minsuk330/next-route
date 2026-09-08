package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.port.out.RemainingRouteCachePort;
import watoo.grd.nextroute.application.journey.service.JourneyWaypointResolver.Waypoints;
import watoo.grd.nextroute.application.route.concurrent.SingleFlight;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.routing.RoutingCacheContext;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingFailure;
import watoo.grd.nextroute.application.route.routing.RoutingOutcome;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingProviderSelector;
import watoo.grd.nextroute.application.route.routing.RoutingQuery;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.route.service.LegacyRouteResponseMapper;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 환승 정류장에서 목적지까지 <b>다시 길찾기</b>. ④에서 처음으로 유료 검색이 나가는 곳이다.
 *
 * <p><b>{@code RouteSearchService} 를 부르지 않는다.</b> 그쪽은 검색이력 저장·telemetry 같은
 * 부수효과를 달고 있어 여정 경로에 섞이면 사용자의 "검색 기록"에 누른 적 없는 검색이 쌓인다.
 * 시작점도 사용자가 입력한 출발지가 아니라 곧 내릴 정류장이다.
 *
 * <h2>요금을 줄이는 두 겹</h2>
 * <ol>
 *   <li><b>캐시</b>(공유 키, TTL 10분) — 다른 사용자의 같은 질문을 막는다</li>
 *   <li><b>SingleFlight</b> — 캐시가 비었을 때 동시에 들어온 같은 질문을 1회로 합친다.
 *       JVM 안에서만 유효하므로 api 인스턴스를 늘리면 인스턴스당 1회로 느슨해진다</li>
 * </ol>
 *
 * <p>어느 쪽이 실패해도 <b>예외를 올리지 않고 빈 목록</b>이다. 재검색은 "있으면 좋은" 기능이라
 * 검색이 죽었다고 재탐색 요청 전체를 실패시키지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemainingRouteSearchService {

    private final RemainingRouteCachePort cache;
    private final OdSayApiPort odSayApiPort;
    private final JourneyRerouteMetrics metrics;
    // provider 전환(national-otp P2). 기본 ODSAY 라 재탐색도 default 에서는 기존 ODsay 직행.
    private final RoutingProviderSelector providerSelector;
    private final LegacyRouteResponseMapper legacyRouteResponseMapper;
    private final watoo.grd.nextroute.application.route.config.OtpRoutingProperties otpRoutingProperties;
    private final Clock clock;

    /** 캐시 값 스키마 버전. 올리면 옛 keyspace 와 분리된다(구형 JSON 을 새 DTO 로 안 읽음). */
    private static final String CACHE_SCHEMA_VERSION = "v1";

    private final SingleFlight<String, List<PathResult>> searchFlight = new SingleFlight<>();

    /**
     * @param stopId    캐시 키가 되는 출발 정류장. <b>미해석(null)이면 부르면 안 된다</b> —
     *                  키가 섞여 다른 정류장의 경로를 서로 돌려준다
     * @param budgetMs  이 검색에 쓸 수 있는 시간. joiner 가 기다리는 상한이기도 하다
     * @return 캐시·검색 모두 실패하면 빈 목록
     */
    public List<PathResult> search(String stopId, Waypoints waypoints, long budgetMs) {
        double destX = waypoints.destination().x();
        double destY = waypoints.destination().y();

        // provider·graph 버전을 캐시 문맥으로 고정한다. ODsay 캐시가 OTP 요청에 섞이거나, graph 재빌드
        // 후 옛 경로가 나가지 않게. provider 는 여기서 한 번 정해 아래 전 경로가 같은 값을 본다.
        RoutingCacheContext cacheCtx = cacheContext();

        Optional<List<PathResult>> cached = findCached(cacheCtx, stopId, destX, destY);
        if (cached.isPresent()) {
            metrics.recordRemainingSearch("cache-hit");
            return cached.get();
        }

        String key = RemainingRouteCachePort.key(cacheCtx, stopId, destX, destY);
        SingleFlight.Outcome<List<PathResult>> outcome;
        try {
            outcome = searchFlight.execute(key, () -> load(cacheCtx, waypoints, stopId, budgetMs), budgetMs);
        } catch (Exception e) {
            // owner 만 여기로 온다. joiner 는 owner 의 실패를 자기 실패로 만들지 않는다.
            log.warn("[JourneyReroute] 남은 구간 검색 실패 [{}]: {}", key, e.getMessage());
            metrics.recordRemainingSearch("failed");
            return List.of();
        }

        if (!outcome.hasValue() || outcome.value().isEmpty()) {
            metrics.recordRemainingSearch("failed");
            return List.of();
        }
        metrics.recordRemainingSearch(
                outcome.role() == SingleFlight.Role.OWNER ? "called" : "single-flight-join");
        return outcome.value();
    }

    /**
     * 실제 호출. <b>{@code paths} 만 쓴다</b> — {@code busCount}·{@code meta} 는 검색 응답 계약용이라
     * 여정 경로에서 쓸 일이 없고, 캐시에도 넣지 않는다.
     */
    private List<PathResult> load(RoutingCacheContext cacheCtx, Waypoints waypoints,
                                  String stopId, long budgetMs) {
        List<PathResult> paths = cacheCtx.provider() == RoutingProvider.OTP
                ? loadOtp(waypoints, budgetMs)
                : loadOdsay(waypoints, budgetMs);
        if (paths.isEmpty()) {
            return List.of();
        }
        store(cacheCtx, stopId, waypoints, paths);
        return paths;
    }

    /** provider(1회 선택) + OTP graphVersion(또는 ODsay sentinel) + 캐시 스키마 버전. */
    private RoutingCacheContext cacheContext() {
        RoutingProvider provider = providerSelector.select(false);
        String graphVersion = provider == RoutingProvider.OTP
                ? otpRoutingProperties.getGraphVersion() : null;   // null → ODSAY_NO_GRAPH sentinel
        return RoutingCacheContext.of(provider, graphVersion, CACHE_SCHEMA_VERSION);
    }

    /** 기본 provider(ODSAY): 기존 직행 유지(parity). */
    private List<PathResult> loadOdsay(Waypoints waypoints, long budgetMs) {
        RouteSearchResult result = odSayApiPort.searchPath(
                waypoints.origin().x(), waypoints.origin().y(),
                waypoints.destination().x(), waypoints.destination().y(), budgetMs);
        if (result == null || result.paths() == null) {
            return List.of();
        }
        return result.paths();
    }

    /**
     * OTP 경로: RoutingPort → legacy paths. 실패는 kind 를 계측하되 재탐색 계약대로 빈 목록으로 끝낸다
     * (실패가 재탐색 요청 전체를 죽이지 않음). 출발시각은 현재 시각 — 예상 환승 도착시각 전달은 시그니처
     * 변경이 필요한 후속 작업이다.
     */
    private List<PathResult> loadOtp(Waypoints waypoints, long budgetMs) {
        RoutingDeadline deadline = RoutingDeadline.of(clock, budgetMs);
        RoutingQuery query = new RoutingQuery(
                waypoints.origin().x(), waypoints.origin().y(), null,
                waypoints.destination().x(), waypoints.destination().y(), null,
                clock.instant(), Set.of(), null, false, "otp-v2", RoutingProvider.OTP, null);
        RoutingOutcome outcome = providerSelector.portFor(RoutingProvider.OTP).search(query, deadline);
        if (outcome instanceof RoutingFailure failure) {
            // 카운터는 search() 가 빈 목록을 보고 "failed" 로 일원 기록한다(이중 기록 방지). 실패 종류는
            // metric 화이트리스트에 없어 unknown 으로 접히므로 로그로 남긴다.
            log.debug("[JourneyReroute] OTP 재탐색 실패 kind={} code={}", failure.kind(), failure.code());
            return List.of();
        }
        return legacyRouteResponseMapper.toLegacy((RoutingResult) outcome).paths();
    }

    /**
     * 캐시 실패는 <b>여기서 끝난다.</b> 어댑터도 삼키지만 그 계약에만 기대지 않는다 — 캐시에 못
     * 넣었다는 이유로 이미 돈을 내고 받아 온 경로를 버리면, Redis 장애가 곧 "제안 없음"이 된다.
     */
    private void store(RoutingCacheContext cacheCtx, String stopId, Waypoints waypoints, List<PathResult> paths) {
        try {
            cache.put(cacheCtx, stopId, waypoints.destination().x(), waypoints.destination().y(), paths);
        } catch (Exception e) {
            log.warn("[JourneyReroute] 남은 구간 캐시 저장 실패 — 검색 결과는 그대로 쓴다: {}",
                    e.getMessage());
        }
    }

    private Optional<List<PathResult>> findCached(RoutingCacheContext cacheCtx, String stopId, double destX, double destY) {
        try {
            return cache.find(cacheCtx, stopId, destX, destY);
        } catch (Exception e) {
            log.warn("[JourneyReroute] 남은 구간 캐시 조회 실패 — 없는 것처럼 진행한다: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
