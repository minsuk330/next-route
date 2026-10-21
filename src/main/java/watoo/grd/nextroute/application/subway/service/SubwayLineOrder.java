package watoo.grd.nextroute.application.subway.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.dto.DirectedRailPattern;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 한 노선·방향에서 어떤 역의 바로 앞·뒤 역(#317).
 *
 * <p>위치 API 의 "전역출발(3)"은 그 행 역의 <b>바로 전역</b>을 떠났다는 뜻이라, 열차를 그리려면 전역이 어디인지 알아야
 * 한다. 원천은 남은 역 목록(#283)과 같은 OTP 패턴이고, 역 연결도 같은 캐시({@link SubwayStationsAheadService#resolvedPatterns})를
 * 쓴다.
 *
 * <p><b>OTP 를 부르지 않는다.</b> 이미 적재된 패턴({@link SubwayLinePatternService#cachedPatterns})만 본다 — 역 도착
 * 응답 지연에 적재를 넣지 않는다. 없으면 모름으로 답하고 뒤에서 한 번만 적재한다({@link SubwayLinePatternService#loadLater}).
 *
 * <p><b>답이 하나일 때만 답한다.</b> 1호선 구로처럼 갈래마다 전역이 다르면 모름이다. 다만 급행 패턴이 건너뛴 역 때문에
 * 생기는 후보(완행은 A→B→X, 급행은 A→X)는 걸러 낸다 — 어느 패턴에서든 X 와의 사이에 다른 후보가 끼는 후보(A)는
 * 버린다. 갈래 합류(C1→X, C2→X)는 서로를 지워 모름이 된다.
 */
@Component
public class SubwayLineOrder {

    private final SubwayLinePatternService patternService;
    private final SubwayLineStationIndex stationIndex;
    private final SubwayStationsAheadService stationsAheadService;

    public SubwayLineOrder(SubwayLinePatternService patternService, SubwayLineStationIndex stationIndex,
                           SubwayStationsAheadService stationsAheadService) {
        this.patternService = patternService;
        this.stationIndex = stationIndex;
        this.stationsAheadService = stationsAheadService;
    }

    /** 이 방향으로 달릴 때 {@code stationId} 바로 앞 역 ID. 모르거나 갈래마다 다르면 empty. */
    public Optional<String> prevOf(String lineId, String direction, String stationId) {
        return neighbor(patterns(lineId, direction), stationId, -1);
    }

    /** 이 방향으로 달릴 때 {@code stationId} 바로 다음 역 ID. 모르거나 갈래마다 다르면 empty. */
    public Optional<String> nextOf(String lineId, String direction, String stationId) {
        return neighbor(patterns(lineId, direction), stationId, +1);
    }

    /**
     * {@code fromStationId} 출발 → 바로 다음 {@code toStationId} 도착 GTFS 계획 초(#317 구간 앵커의 대체값). 그 둘이 붙어
     * 있는 패턴 중 trip 이 가장 많은 것. 모르면 empty.
     */
    public Optional<Integer> runSeconds(String lineId, String direction, String fromStationId, String toStationId) {
        if (fromStationId == null || toStationId == null) {
            return Optional.empty();
        }
        StationsAheadCalculator.Pattern best = null;
        Integer seconds = null;
        for (StationsAheadCalculator.Pattern pattern : patterns(lineId, direction)) {
            List<StationsAheadCalculator.Stop> stops = pattern.stops();
            for (int i = 0; i + 1 < stops.size(); i++) {
                if (fromStationId.equals(stops.get(i).stationId()) && toStationId.equals(stops.get(i + 1).stationId())
                        && (best == null || pattern.tripCount() > best.tripCount())) {
                    Integer s = StationsAheadCalculator.seconds(stops.get(i).departureSec(), stops.get(i + 1).arrivalSec());
                    if (s != null) {
                        best = pattern;
                        seconds = s;
                    }
                }
            }
        }
        return Optional.ofNullable(seconds);
    }

    /**
     * 바로 붙은 두 역 구간의 GTFS 계획 시간 — {@code from} 정차(도착 → 출발)와 운행(출발 → {@code to} 도착)을 <b>한 패턴</b>에서
     * 뽑는다(진행 링 TYPICAL 직선). 둘이 붙어 있는 패턴 중 trip 이 가장 많은 것. 운행을 모르면 empty, 정차는 모르거나 0 이하면
     * null.
     */
    public Optional<SegmentTimes> segmentTimes(String lineId, String direction, String fromStationId,
                                               String toStationId) {
        if (fromStationId == null || toStationId == null) {
            return Optional.empty();
        }
        StationsAheadCalculator.Pattern best = null;
        SegmentTimes times = null;
        for (StationsAheadCalculator.Pattern pattern : patterns(lineId, direction)) {
            List<StationsAheadCalculator.Stop> stops = pattern.stops();
            for (int i = 0; i + 1 < stops.size(); i++) {
                if (fromStationId.equals(stops.get(i).stationId()) && toStationId.equals(stops.get(i + 1).stationId())
                        && (best == null || pattern.tripCount() > best.tripCount())) {
                    Integer run = StationsAheadCalculator.seconds(stops.get(i).departureSec(), stops.get(i + 1).arrivalSec());
                    if (run != null) {
                        Integer dwell = StationsAheadCalculator.seconds(stops.get(i).arrivalSec(), stops.get(i).departureSec());
                        best = pattern;
                        times = new SegmentTimes(dwell == null || dwell <= 0 ? null : dwell, run);
                    }
                }
            }
        }
        return Optional.ofNullable(times);
    }

    /**
     * 이 방향으로 달릴 때 {@code stationId} 앞쪽(열차가 오는 쪽) 최대 {@code n} 역, 먼 역 → 가까운 역(#368 다가오는 열차).
     * 역 도착 노선도(#317)와 같은 {@link StationsAheadCalculator#behind} 다 — 갈래가 갈리는 합류점 너머는 자른다. 모르면 빈 목록.
     */
    public List<StationsAheadCalculator.Stop> behind(String lineId, String direction, String stationId, int n) {
        if (stationId == null || n <= 0) {
            return List.of();
        }
        return StationsAheadCalculator.behind(patterns(lineId, direction), stationId, n).stops();
    }

    /**
     * 이 방향으로 {@code n} 역 앞(열차가 오는 쪽) 역을 출발해 {@code stationId} 에 닿기까지의 계획 초(#480). 남은 초 없이
     * "[N]번째 전역"으로만 답하는 열차의 도착을 어림한다. 그 앞이 갈래로 갈리거나 기점이라 {@code n} 역을 못 채우면 빈 값.
     */
    public Optional<Integer> secondsFromBehind(String lineId, String direction, String stationId, int n) {
        if (stationId == null || n <= 0) {
            return Optional.empty();
        }
        StationsAheadCalculator.Behind behind = StationsAheadCalculator.behind(patterns(lineId, direction), stationId, n);
        if (behind.stops().size() < n || behind.secondsToHere().isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(behind.secondsToHere().get(0));
    }

    /** @see #segmentTimes */
    public record SegmentTimes(Integer dwellSeconds, int runSeconds) {
    }

    // ── 급행 정차 패턴(subway-express PR-S4) ─────────────────────────────────

    /**
     * 급행 열차의 정차역 두 곳({@code from} → 바로 다음 정차역 {@code to}) GTFS 계획 시간. {@code pattern} 이 null 이면
     * {@link #segmentTimes(String, String, String, String)} 와 같다(일반 열차).
     *
     * <p><b>정차열이 정확히 같은 OTP 패턴만</b> 쓴다: 노선형 급행 패턴({@link SubwayLinePatternService#cachedExpressPatterns})과
     * 일반 노선 안의 패턴 중 {@code from}·{@code to} 가 붙어 있고, 겹치는 구간 전체의 정차열이 열차의 정차열과 같은 것. 없으면
     * empty — 완행 구간 합으로 지어내지 않는다(9호선 급행 가양→염창 계획 210초 vs 완행 합−정차 310초, 20~60% 과대).
     */
    public Optional<SegmentTimes> segmentTimes(String lineId, String direction, String fromStationId,
                                               String toStationId, SubwayStopPatternResolver.ResolvedPattern pattern) {
        if (pattern == null) {
            return segmentTimes(lineId, direction, fromStationId, toStationId);
        }
        int a = pattern.stopIds().indexOf(fromStationId);
        if (fromStationId == null || toStationId == null || a < 0 || a + 1 >= pattern.stopIds().size()
                || !toStationId.equals(pattern.stopIds().get(a + 1))) {
            return Optional.empty();
        }
        Match best = bestExactMatch(lineId, direction, pattern.stopIds(), a, a + 1);
        if (best == null) {
            return Optional.empty();
        }
        List<StationsAheadCalculator.Stop> stops = best.pattern().stops();
        Integer run = StationsAheadCalculator.seconds(stops.get(best.index()).departureSec(),
                stops.get(best.index() + 1).arrivalSec());
        if (run == null) {
            return Optional.empty();
        }
        Integer dwell = StationsAheadCalculator.seconds(stops.get(best.index()).arrivalSec(),
                stops.get(best.index()).departureSec());
        return Optional.of(new SegmentTimes(dwell == null || dwell <= 0 ? null : dwell, run));
    }

    /**
     * 급행 열차의 정차역 {@code from} 도착 → 그 뒤 정차역 {@code to} 도착 GTFS 계획 초. {@code pattern} 이 null 이면
     * {@link #arrivalGapSeconds(String, String, String, String)} 와 같다. 정차열이 정확히 같은 패턴만, 없으면 empty.
     */
    public Optional<Integer> arrivalGapSeconds(String lineId, String direction, String fromStationId,
                                               String toStationId, SubwayStopPatternResolver.ResolvedPattern pattern) {
        if (pattern == null) {
            return arrivalGapSeconds(lineId, direction, fromStationId, toStationId);
        }
        if (fromStationId == null || toStationId == null) {
            return Optional.empty();
        }
        if (fromStationId.equals(toStationId)) {
            return Optional.of(0);
        }
        int a = pattern.stopIds().indexOf(fromStationId);
        int b = pattern.stopIds().indexOf(toStationId);
        if (a < 0 || b <= a) {
            return Optional.empty();
        }
        Match best = bestExactMatch(lineId, direction, pattern.stopIds(), a, b);
        if (best == null) {
            return Optional.empty();
        }
        List<StationsAheadCalculator.Stop> stops = best.pattern().stops();
        return Optional.ofNullable(StationsAheadCalculator.seconds(arrivalOf(stops.get(best.index())),
                arrivalOf(stops.get(best.index() + (b - a)))));
    }

    /** OTP 패턴 하나에서 열차 정차열 {@code [a..b]} 가 시작하는 자리. */
    private record Match(StationsAheadCalculator.Pattern pattern, int index) {
    }

    /** 정차열이 정확히 같은 패턴 중 trip 최다. 노선형 급행 패턴과 일반 노선 패턴(정차 패턴형 급행 trip)을 함께 본다. */
    private Match bestExactMatch(String lineId, String direction, List<String> stopIds, int a, int b) {
        Match best = null;
        List<StationsAheadCalculator.Pattern> candidates = new ArrayList<>(expressPatterns(lineId, direction));
        candidates.addAll(patterns(lineId, direction));
        for (StationsAheadCalculator.Pattern pattern : candidates) {
            int at = exactMatch(pattern.stops(), stopIds, a, b);
            if (at >= 0 && (best == null || pattern.tripCount() > best.pattern().tripCount())) {
                best = new Match(pattern, at);
            }
        }
        return best;
    }

    /**
     * {@code stops} 에서 열차 정차열 {@code stopIds[a..b]} 가 빈틈없이 이어지고, 그 앞뒤로 둘이 겹치는 동안 정차열이 끝까지 같으면
     * {@code stopIds[a]} 의 자리, 아니면 -1. 겹치는 구간에 한 역이라도 다르면(다른 급행 서비스·완행) 쓰지 않는다.
     */
    static int exactMatch(List<StationsAheadCalculator.Stop> stops, List<String> stopIds, int a, int b) {
        String first = stopIds.get(a);
        for (int i = 0; i < stops.size(); i++) {
            if (!first.equals(stops.get(i).stationId())) {
                continue;
            }
            int offset = i - a;
            int lo = Math.max(0, -offset);
            int hi = Math.min(stopIds.size() - 1, stops.size() - 1 - offset);
            if (b > hi) {
                continue;
            }
            boolean same = true;
            for (int k = lo; k <= hi && same; k++) {
                same = stopIds.get(k).equals(stops.get(k + offset).stationId());
            }
            if (same) {
                return i;
            }
        }
        return -1;
    }

    /** 이미 적재된 노선형 급행 패턴. 없으면 빈 목록이고, 한 번도 안 받았으면 뒤에서 적재를 건다. */
    private List<StationsAheadCalculator.Pattern> expressPatterns(String lineId, String direction) {
        if (lineId == null || direction == null) {
            return List.of();
        }
        List<DirectedRailPattern> directed = patternService.cachedExpressPatterns(lineId, direction);
        if (directed.isEmpty()) {
            if (!patternService.expressLoaded(lineId, direction)) {
                patternService.loadExpressLater(lineId, direction);
            }
            return List.of();
        }
        // 역 연결 캐시 키를 일반 패턴과 나눈다(같은 키면 두 목록이 번갈아 캐시를 덮는다)
        return stationsAheadService.resolvedPatterns(lineId, direction + ":express", directed,
                stationIndex.candidates(lineId));
    }

    /**
     * 이 방향으로 {@code boardStationId} → {@code alightStationId} 를 완행으로 갈 때 지나는 역(양 끝 포함, subway-express).
     * 두 역을 이 순서로 지나는 캐시 패턴 중 <b>사이 역이 가장 많은 것</b> — 같은 노선 일반 노선에 섞인 급행 trip 패턴
     * (수인분당 {@code SD-1D::05} 등)보다 완행이 길다. 같으면 trip 이 많은 것. 순환 패턴은 승차역이 다시 나오기 전까지만
     * 본다. 모르면 빈 목록 — OTP 를 부르지 않는다(없으면 뒤에서 적재만 건다).
     *
     * @return 역 id. 역에 못 이은 정차는 {@code name:…} 키로 들어간다(사이 역 개수를 지키려고)
     */
    public List<String> allStopPath(String lineId, String direction, String boardStationId, String alightStationId) {
        if (boardStationId == null || alightStationId == null) {
            return List.of();
        }
        return allStopPath(patterns(lineId, direction), boardStationId, alightStationId);
    }

    static List<String> allStopPath(List<StationsAheadCalculator.Pattern> patterns, String boardStationId,
                                    String alightStationId) {
        if (boardStationId.equals(alightStationId)) {
            return List.of(boardStationId);
        }
        List<String> best = List.of();
        int bestTrips = -1;
        for (StationsAheadCalculator.Pattern pattern : patterns) {
            List<StationsAheadCalculator.Stop> stops = pattern.stops();
            for (int i = 0; i < stops.size(); i++) {
                if (!boardStationId.equals(stops.get(i).key())) {
                    continue;
                }
                for (int j = i + 1; j < stops.size(); j++) {
                    String key = stops.get(j).key();
                    if (boardStationId.equals(key)) {
                        break; // 한 바퀴 돌았다
                    }
                    if (alightStationId.equals(key)) {
                        int size = j - i + 1;
                        if (size > best.size() || (size == best.size() && pattern.tripCount() > bestTrips)) {
                            best = stops.subList(i, j + 1).stream().map(StationsAheadCalculator.Stop::key).toList();
                            bestTrips = pattern.tripCount();
                        }
                        break;
                    }
                }
            }
        }
        return best;
    }

    /**
     * {@code fromStationId} 도착 → 그 뒤 {@code toStationId} 도착까지 GTFS 계획 초(운행 + 사이 역 정차). 진행 링의 하차역 도착
     * 예정(#310)이 다음 역 예정에 더한다. 두 역을 이 순서로 지나는 패턴 중 trip 이 가장 많은 것(완행이 보통 더 많다). 순환
     * 패턴은 {@code from} 다음 첫 {@code to} 까지만 본다. 모르면 empty. 같은 역이면 0.
     */
    public Optional<Integer> arrivalGapSeconds(String lineId, String direction, String fromStationId,
                                               String toStationId) {
        if (fromStationId == null || toStationId == null) {
            return Optional.empty();
        }
        if (fromStationId.equals(toStationId)) {
            return Optional.of(0);
        }
        StationsAheadCalculator.Pattern best = null;
        Integer seconds = null;
        for (StationsAheadCalculator.Pattern pattern : patterns(lineId, direction)) {
            Integer gap = arrivalGap(pattern.stops(), fromStationId, toStationId);
            if (gap != null && (best == null || pattern.tripCount() > best.tripCount())) {
                best = pattern;
                seconds = gap;
            }
        }
        return Optional.ofNullable(seconds);
    }

    static Integer arrivalGap(List<StationsAheadCalculator.Stop> stops, String fromStationId, String toStationId) {
        for (int i = 0; i < stops.size(); i++) {
            if (!fromStationId.equals(stops.get(i).stationId())) {
                continue;
            }
            for (int j = i + 1; j < stops.size(); j++) {
                String id = stops.get(j).stationId();
                if (fromStationId.equals(id)) {
                    break; // 한 바퀴 돌았다
                }
                if (toStationId.equals(id)) {
                    return StationsAheadCalculator.seconds(arrivalOf(stops.get(i)), arrivalOf(stops.get(j)));
                }
            }
        }
        return null;
    }

    private static Integer arrivalOf(StationsAheadCalculator.Stop stop) {
        return stop.arrivalSec() != null ? stop.arrivalSec() : stop.departureSec();
    }

    private List<StationsAheadCalculator.Pattern> patterns(String lineId, String direction) {
        if (lineId == null || direction == null) {
            return List.of();
        }
        List<DirectedRailPattern> directed = patternService.cachedPatterns(lineId, direction);
        if (directed.isEmpty()) {
            patternService.loadLater(lineId, direction);
            return List.of();
        }
        return stationsAheadService.resolvedPatterns(lineId, direction, directed, stationIndex.candidates(lineId));
    }

    /**
     * @param step -1 이면 앞 역, +1 이면 다음 역
     */
    static Optional<String> neighbor(List<StationsAheadCalculator.Pattern> patterns, String stationId, int step) {
        if (stationId == null) {
            return Optional.empty();
        }
        Set<String> candidates = new LinkedHashSet<>();
        for (StationsAheadCalculator.Pattern pattern : patterns) {
            List<StationsAheadCalculator.Stop> stops = pattern.stops();
            for (int i = 0; i < stops.size(); i++) {
                int j = i + step;
                if (stationId.equals(stops.get(i).stationId()) && j >= 0 && j < stops.size()) {
                    String neighbor = stops.get(j).stationId();
                    if (neighbor != null && !neighbor.equals(stationId)) {
                        candidates.add(neighbor);
                    }
                }
            }
        }
        List<String> kept = new ArrayList<>(candidates);
        kept.removeIf(candidate -> skippedOver(patterns, candidate, candidates, stationId, step));
        return kept.size() == 1 ? Optional.of(kept.get(0)) : Optional.empty();
    }

    /**
     * {@code candidate} 와 {@code stationId} 사이에 다른 후보가 끼는 패턴이 있으면 {@code candidate} 는 급행이 건너뛴
     * 자리다(완행 기준으로는 더 먼 역).
     */
    private static boolean skippedOver(List<StationsAheadCalculator.Pattern> patterns, String candidate,
                                       Set<String> candidates, String stationId, int step) {
        for (StationsAheadCalculator.Pattern pattern : patterns) {
            List<StationsAheadCalculator.Stop> stops = pattern.stops();
            for (int i = 0; i < stops.size(); i++) {
                if (!stationId.equals(stops.get(i).stationId())) {
                    continue;
                }
                // stationId 에서 step 쪽으로 가며 candidate 를 찾는다. 그 전에 다른 후보를 지났으면 candidate 는 건너뛴 자리다.
                // candidate 가 없는 패턴(급행)은 근거가 되지 않는다.
                boolean passedOther = false;
                for (int j = i + step; j >= 0 && j < stops.size(); j += step) {
                    String id = stops.get(j).stationId();
                    if (candidate.equals(id)) {
                        if (passedOther) {
                            return true;
                        }
                        break;
                    }
                    if (stationId.equals(id)) {
                        break; // 순환 패턴이 한 바퀴 돌았다
                    }
                    if (id != null && candidates.contains(id)) {
                        passedOther = true;
                    }
                }
            }
        }
        return false;
    }
}
