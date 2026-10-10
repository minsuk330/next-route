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

    /** @see #segmentTimes */
    public record SegmentTimes(Integer dwellSeconds, int runSeconds) {
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
