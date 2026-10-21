package watoo.grd.nextroute.application.route.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.dto.OtpRailRoute;
import watoo.grd.nextroute.application.subway.dto.TrainKind;
import watoo.grd.nextroute.application.subway.service.SubwayExpressCatalog;
import watoo.grd.nextroute.application.subway.service.SubwayLinePatternService;
import watoo.grd.nextroute.application.subway.service.SubwayLineStationIndex;
import watoo.grd.nextroute.application.subway.service.SubwayPatternStationMatcher;
import watoo.grd.nextroute.application.subway.service.SubwayRoutePatternAssembler;
import watoo.grd.nextroute.application.subway.service.SubwayStopPatternResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 경로 검색 철도 구간의 열차 종류(subway-express PR-S2). 판정은 이 한 곳이다 — OTP 어댑터 경계
 * ({@code OtpRoutingAdapter.searchOnce})에서 본·추가·재탐색 검색 모두 {@link #annotate} 를 거치고, 어댑터를 거치지 않는
 * 단독 추적만 {@link #classify} 를 직접 부른다.
 *
 * <h2>규칙(순서대로)</h2>
 * <ol>
 *   <li>버스·도보 → null</li>
 *   <li>ITX 노선({@code 1:RR_ACC4_NATZ200_U/D}, 이름에 {@code ITX}) → ITX. 경로에는 남기고 추적은 안 한다(D4)</li>
 *   <li>노선 긴 이름에 {@code 직통} → DIRECT(안전장치. 운영 그래프에는 공항철도 직통이 없다, D3)</li>
 *   <li>급행 카탈로그에 서비스가 없는 노선(2·3·5호선, 공항철도, 간선 철도 등) → NORMAL</li>
 *   <li><b>구간 기준</b>: 승·하차역 사이 정차열을 완행 순서({@link SubwayStopPatternResolver#allStopPathEitherDirection})와
 *       맞대어 <b>한 역이라도 건너뛰면</b> EXPRESS, 아니면 NORMAL. 노선 이름이 급행이어도 건너뛴 역이 없으면 NORMAL 이다 —
 *       OTP 는 1호선 '구로 급행'(14xx, 용산~구로 모든 역 정차)을 {@code 서울1호선(용산-동인천급행)} 노선의 7역 패턴에
 *       넣는데 S1 이 이 열차를 일반으로 정했다. 같은 이유로 급행 trip 이어도 봉은사→종합운동장처럼 붙은 두 정차역만 타면
 *       NORMAL(실제로 건너뛰는 역이 없다). 같은 노선 안에 급행 trip 이 섞인 정차 패턴형(수인분당 {@code SD-1D::05} 등)도
 *       같은 규칙이다</li>
 *   <li>완행 순서를 모르면(지하철 패턴 캐시가 아직 비었거나 역을 못 이음): 노선 긴 이름에 {@code 급행} 이면 EXPRESS
 *       (9호선 급행 노선은 모든 trip 이 급행 정차만 한다 — 운영 fixture 2026-10-07), 아니면 NORMAL. 부팅 직후 몇 초·worker 의
 *       노선별 첫 재탐색에서만 생긴다(캐시를 비운 채 OTP 를 부르지 않는다)</li>
 * </ol>
 *
 * <p>{@link #expressRouteIds} 는 OTP 가 노선 단위로 거를 수 있는 <b>노선형 급행</b> route id 다(급행 끄기·일반 대안 되찾기).
 * 정차 패턴형 급행은 노선 단위로 못 걸러 사후 필터({@code ExpressFilterPolicy})가 맡는다.
 */
@Slf4j
@Component
public class RailServiceClassifier {

    /** 노선형 급행 route(9호선 급행, 1호선 경인·경부 급행). 운영 OTP 2026-10-21 노선 목록 대조. */
    static final Set<String> EXPRESS_ROUTE_IDS = Set.of(
            "1:RR_ACC1_S-1-09-2U", "1:RR_ACC1_S-1-09-2D",
            "1:RR_ACC1_S-1-01-4U", "1:RR_ACC1_S-1-01-4D",
            "1:RR_ACC1_S-1-01-6U", "1:RR_ACC1_S-1-01-6D");

    /** ITX-청춘 route({@code [일반]ITX청춘(천마산.신내)}). */
    static final Set<String> ITX_ROUTE_IDS = Set.of("1:RR_ACC4_NATZ200_U", "1:RR_ACC4_NATZ200_D");

    private static final String EXPRESS_WORD = "급행";
    private static final String DIRECT_WORD = "직통";
    private static final String ITX_WORD = "ITX";

    private final SubwayExpressCatalog catalog;
    private final SubwayStopPatternResolver resolver;
    private final SubwayLineStationIndex stationIndex;
    private final SubwayLinePatternService patternService;
    private final AtomicBoolean routesVerified = new AtomicBoolean(false);

    public RailServiceClassifier(SubwayExpressCatalog catalog, SubwayStopPatternResolver resolver,
                                 SubwayLineStationIndex stationIndex, SubwayLinePatternService patternService) {
        this.catalog = catalog;
        this.resolver = resolver;
        this.stationIndex = stationIndex;
        this.patternService = patternService;
    }

    /**
     * 완행 순서로 펼친 정차 하나.
     *
     * @param key        역 비교 키(역 id, 못 이으면 {@code name:} + 이름)
     * @param stationId  역 마스터 id. 못 이었으면 null
     * @param trainStops 이 구간 열차가 서는 역인가(계획 정차열에 있는가)
     */
    public record RailStop(String key, String stationId, String name, Double lon, Double lat, boolean trainStops) {
    }

    /** 계획 정차 하나(OTP 정차 이름·좌표). */
    public record PlannedStop(String name, double lon, double lat) {
    }

    /** OTP 가 노선 단위로 거를 수 있는 노선형 급행 route id. */
    public Set<String> expressRouteIds() {
        return EXPRESS_ROUTE_IDS;
    }

    /** 결과의 모든 철도 구간에 열차 종류를 붙인다. 실패 결과는 그대로. */
    public RoutingOutcome annotate(RoutingOutcome outcome) {
        if (outcome instanceof RoutingResult result) {
            return annotate(result);
        }
        return outcome;
    }

    public RoutingResult annotate(RoutingResult result) {
        verifyExpressRoutesOnce();
        List<RoutingItinerary> itineraries = new ArrayList<>(result.itineraries().size());
        for (RoutingItinerary it : result.itineraries()) {
            List<RoutingLeg> legs = new ArrayList<>(it.legs().size());
            for (RoutingLeg leg : it.legs()) {
                TrainKind kind = classify(leg);
                legs.add(kind == leg.trainKind() ? leg : leg.withTrainKind(kind));
            }
            itineraries.add(it.withLegs(legs));
        }
        return result.withItineraries(itineraries);
    }

    /** 규칙은 클래스 주석. 철도가 아니면 null. */
    public TrainKind classify(RoutingLeg leg) {
        if (leg == null || (leg.mode() != RoutingMode.SUBWAY && leg.mode() != RoutingMode.RAIL)) {
            return null;
        }
        String routeId = leg.sourceRouteId();
        String shortName = leg.sourceRouteName();
        String longName = leg.sourceRouteLongName();
        if ((routeId != null && ITX_ROUTE_IDS.contains(routeId)) || contains(shortName, ITX_WORD)
                || contains(longName, ITX_WORD)) {
            return TrainKind.ITX;
        }
        if (contains(longName, DIRECT_WORD)) {
            return TrainKind.DIRECT;
        }
        Optional<String> lineId = SubwayRoutePatternAssembler.lineIdOf(shortName);
        if (lineId.isEmpty() || !catalogLines().contains(lineId.get())) {
            return TrainKind.NORMAL;
        }
        String preferred = SubwayRoutePatternAssembler.realtimeDirection(routeId, longName);
        Optional<List<RailStop>> expanded = expand(lineId.get(), preferred, planned(leg));
        if (expanded.isPresent()) {
            return skipsAny(expanded.get()) ? TrainKind.EXPRESS : TrainKind.NORMAL;
        }
        return contains(longName, EXPRESS_WORD) ? TrainKind.EXPRESS : TrainKind.NORMAL;
    }

    /**
     * 철도 구간 하나를 완행 순서로 펼친다(geometry {@code allStops}). 계획 정차열의 첫·끝 역 사이 완행 경로에 각 역이
     * 계획 정차열에 있는지({@code trainStops}) 표시한다.
     *
     * @param routeName 노선 짧은 이름({@code 서울9호선}). 서울 지하철 노선이 아니면 empty
     * @return 완행 순서를 모르거나 계획 정차가 그 경로 밖이면 empty
     */
    public Optional<List<RailStop>> expandAllStops(String routeName, List<PlannedStop> planned) {
        Optional<String> lineId = SubwayRoutePatternAssembler.lineIdOf(routeName);
        if (lineId.isEmpty()) {
            return Optional.empty();
        }
        return expand(lineId.get(), null, planned);
    }

    /** 펼친 경로에 서지 않는 역이 있는가. */
    public static boolean skipsAny(List<RailStop> stops) {
        return stops.stream().anyMatch(s -> !s.trainStops());
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    private Optional<List<RailStop>> expand(String lineId, String preferredDirection, List<PlannedStop> planned) {
        if (planned.size() < 2) {
            return Optional.empty();
        }
        List<SubwayPatternStationMatcher.Candidate> candidates = stationIndex.candidates(lineId);
        List<String> keys = new ArrayList<>(planned.size());
        for (PlannedStop stop : planned) {
            keys.add(SubwayPatternStationMatcher.key(stop.name(), stop.lat(), stop.lon(), candidates));
        }
        List<String> path = resolver.allStopPathEitherDirection(lineId, preferredDirection,
                keys.get(0), keys.get(keys.size() - 1));
        if (path.isEmpty() || !inOrder(path, keys)) {
            // 계획 정차가 완행 경로 밖이거나 순서가 다르다 — 패턴·역 잇기가 어긋났다. 지어내지 않는다
            return Optional.empty();
        }
        Set<String> plannedKeys = new HashSet<>(keys);
        List<RailStop> out = new ArrayList<>(path.size());
        for (String key : path) {
            int at = keys.indexOf(key);
            PlannedStop own = at < 0 ? null : planned.get(at);
            SubwayPatternStationMatcher.Candidate candidate = key.startsWith("name:") ? null
                    : candidates.stream().filter(c -> key.equals(c.stationId())).findFirst().orElse(null);
            String name = candidate != null ? candidate.stationName()
                    : own != null ? SubwayPatternStationMatcher.displayName(own.name()) : key.substring("name:".length());
            Double lon = own != null ? Double.valueOf(own.lon()) : candidate == null ? null : candidate.lon();
            Double lat = own != null ? Double.valueOf(own.lat()) : candidate == null ? null : candidate.lat();
            out.add(new RailStop(key, candidate == null ? null : candidate.stationId(), name, lon, lat,
                    plannedKeys.contains(key)));
        }
        return Optional.of(List.copyOf(out));
    }

    /** 계획 정차열이 완행 경로의 부분수열인가. */
    private static boolean inOrder(List<String> path, List<String> keys) {
        int from = 0;
        for (String key : keys) {
            int at = path.subList(from, path.size()).indexOf(key);
            if (at < 0) {
                return false;
            }
            from += at + 1;
        }
        return true;
    }

    private static List<PlannedStop> planned(RoutingLeg leg) {
        List<PlannedStop> out = new ArrayList<>(leg.stops().size());
        for (StopOccurrence stop : leg.stops()) {
            if (stop.name() == null) {
                return List.of();
            }
            out.add(new PlannedStop(stop.name(), stop.lon(), stop.lat()));
        }
        return out;
    }

    private Set<String> catalogLines() {
        return catalog.services().stream().map(SubwayExpressCatalog.ExpressService::lineId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 노선형 급행 route id 가 지금 그래프에 있는지 한 번 대조한다(그래프 재빌드 때 id 가 바뀌면 급행 끄기가 소리 없이
     * 안 먹는다). 지하철 노선 목록이 캐시에 있을 때만 — OTP 를 부르지 않는다.
     */
    void verifyExpressRoutesOnce() {
        if (routesVerified.get()) {
            return;
        }
        List<OtpRailRoute> routes = patternService.cachedRoutes();
        if (routes.isEmpty() || !routesVerified.compareAndSet(false, true)) {
            return;
        }
        Set<String> present = routes.stream().map(OtpRailRoute::gtfsId).collect(Collectors.toSet());
        List<String> missing = EXPRESS_ROUTE_IDS.stream().filter(id -> !present.contains(id)).sorted().toList();
        if (missing.isEmpty()) {
            log.info("[Route] 노선형 급행 route {}개 그래프 대조 통과", EXPRESS_ROUTE_IDS.size());
        } else {
            log.warn("[Route] 노선형 급행 route 가 그래프에 없다 — 급행 끄기·일반 대안 되찾기가 이 노선에서 안 먹는다 missing={}",
                    missing);
        }
    }

    private static boolean contains(String value, String word) {
        return value != null && value.contains(word);
    }
}
