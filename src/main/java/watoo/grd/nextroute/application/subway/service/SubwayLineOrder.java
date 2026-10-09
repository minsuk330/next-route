package watoo.grd.nextroute.application.subway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.dto.DirectedRailPattern;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 한 노선·방향에서 어떤 역의 바로 앞·뒤 역(#317).
 *
 * <p>위치 API 의 "전역출발(3)"은 그 행 역의 <b>바로 전역</b>을 떠났다는 뜻이라, 열차를 그리려면 전역이 어디인지 알아야
 * 한다. 원천은 남은 역 목록(#283)과 같은 OTP 패턴이고, 역 연결도 같은 캐시({@link SubwayStationsAheadService#resolvedPatterns})를
 * 쓴다.
 *
 * <p><b>OTP 를 부르지 않는다.</b> 이미 적재된 패턴({@link SubwayLinePatternService#cachedPatterns})만 본다 — 역 도착
 * 응답 지연에 적재를 넣지 않는다. 없으면 모름으로 답하고 뒤에서 한 번만 적재한다.
 *
 * <p><b>답이 하나일 때만 답한다.</b> 1호선 구로처럼 갈래마다 전역이 다르면 모름이다. 다만 급행 패턴이 건너뛴 역 때문에
 * 생기는 후보(완행은 A→B→X, 급행은 A→X)는 걸러 낸다 — 어느 패턴에서든 X 와의 사이에 다른 후보가 끼는 후보(A)는
 * 버린다. 갈래 합류(C1→X, C2→X)는 서로를 지워 모름이 된다.
 */
@Slf4j
@Component
public class SubwayLineOrder {

    private final SubwayLinePatternService patternService;
    private final SubwayLineStationIndex stationIndex;
    private final SubwayStationsAheadService stationsAheadService;

    private final Set<String> loading = ConcurrentHashMap.newKeySet();
    private final ExecutorService loader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "subway-line-order-load");
        thread.setDaemon(true);
        return thread;
    });

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

    private List<StationsAheadCalculator.Pattern> patterns(String lineId, String direction) {
        if (lineId == null || direction == null) {
            return List.of();
        }
        List<DirectedRailPattern> directed = patternService.cachedPatterns(lineId, direction);
        if (directed.isEmpty()) {
            loadInBackground(lineId, direction);
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

    private void loadInBackground(String lineId, String direction) {
        String key = lineId + ":" + direction;
        if (!loading.add(key)) {
            return;
        }
        loader.execute(() -> {
            try {
                patternService.patterns(lineId, direction);
            } catch (RuntimeException e) {
                log.warn("[OTP] 노선 순서 패턴 적재 실패 line={} direction={}: {}", lineId, direction, e.getMessage());
            } finally {
                loading.remove(key);
            }
        });
    }
}
