package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.dto.DirectedRailPattern;
import watoo.grd.nextroute.application.subway.dto.RailPattern;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 서울 실시간 역 id → OTP GTFS 정류장 id(#298 A안). 근처 지하철역 응답이 단독 추적 시작에 넘길 값을 싣게 한다.
 *
 * <p>대응 규칙은 남은 역 목록(#283 {@code SubwayStationsAheadService.resolve})과 <b>같다</b> — 같은 노선의 OTP 패턴 정차를
 * {@link SubwayPatternStationMatcher} 로 역 마스터에 잇는다. 서울 역 id 와 GTFS id 사이에 결정적 규칙이 없어서다.
 *
 * <p><b>OTP 를 부르지 않는다.</b> 이미 적재된 패턴({@link SubwayLinePatternService#cachedPatterns})만 본다 — 근처 조회는
 * 홈 화면의 첫 요청이라 노선당 1~2.5초 적재를 넣을 수 없다. 기동 워밍업({@link SubwayLinePatternWarmup}) 전에는 비어 있다.
 */
@Component
@RequiredArgsConstructor
public class SubwayGtfsStopIndex {

    private final SubwayLinePatternService patternService;
    private final SubwayLineStationIndex stationIndex;

    /** 노선별 맵. 원본 패턴 묶음이 같으면 다시 잇지 않는다(워밍업·갱신으로 바뀌면 다시). */
    private final Map<String, Built> built = new ConcurrentHashMap<>();

    private record Built(List<List<DirectedRailPattern>> source, Map<String, String> gtfsByStation) {
    }

    public Optional<String> gtfsStopId(String lineId, String stationId) {
        if (lineId == null || stationId == null) {
            return Optional.empty();
        }
        List<List<DirectedRailPattern>> source = new ArrayList<>();
        for (String direction : SubwayLinePatternWarmup.directions(lineId)) {
            source.add(patternService.cachedPatterns(lineId, direction));
        }
        if (source.stream().allMatch(List::isEmpty)) {
            return Optional.empty();
        }
        Built current = built.get(lineId);
        if (current == null || !sameSource(current.source(), source)) {
            current = new Built(source, build(lineId, source));
            built.put(lineId, current);
        }
        return Optional.ofNullable(current.gtfsByStation().get(stationId));
    }

    private Map<String, String> build(String lineId, List<List<DirectedRailPattern>> source) {
        List<SubwayPatternStationMatcher.Candidate> candidates = stationIndex.candidates(lineId);
        Map<String, String> map = new HashMap<>();
        for (List<DirectedRailPattern> patterns : source) {
            for (DirectedRailPattern pattern : patterns) {
                for (RailPattern.Stop stop : pattern.stops()) {
                    if (stop.gtfsId() == null) {
                        continue;
                    }
                    SubwayPatternStationMatcher.match(stop.name(), stop.lat(), stop.lon(), candidates)
                            .ifPresent(c -> map.putIfAbsent(c.stationId(), stop.gtfsId()));
                }
            }
        }
        return Map.copyOf(map);
    }

    private static boolean sameSource(List<List<DirectedRailPattern>> a, List<List<DirectedRailPattern>> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }
}
