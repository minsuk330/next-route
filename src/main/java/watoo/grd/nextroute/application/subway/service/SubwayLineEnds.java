package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.dto.DirectedRailPattern;
import watoo.grd.nextroute.application.subway.dto.RailPattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 역이 노선 끝(종착) 역인지(#335). 종착역은 도착 선로가 번갈아 바뀌어 같은 승강장이라도 내리는 문이 뒤집히므로
 * 문 방향을 내지 않는 데 쓴다.
 *
 * <p>원천은 이미 데워 둔 패턴 캐시({@link SubwayLinePatternService#cachedPatterns}) — 여기서 OTP 를 부르지 않는다.
 * 캐시가 비면 모른다고 답하고 뒤에서 한 번 적재를 건다.
 */
@Component
@RequiredArgsConstructor
public class SubwayLineEnds {

    private final SubwayLinePatternService patternService;

    /**
     * @return true = 노선 끝 역, false = 아님, empty = 모름(패턴 캐시가 비었다)
     */
    public Optional<Boolean> isLineEnd(String lineId, String stationName) {
        if (lineId == null || stationName == null) {
            return Optional.empty();
        }
        List<DirectedRailPattern> patterns = new ArrayList<>();
        for (String direction : SubwayLinePatternWarmup.directions(lineId)) {
            List<DirectedRailPattern> cached = patternService.cachedPatterns(lineId, direction);
            if (cached.isEmpty()) {
                patternService.loadLater(lineId, direction);
            }
            patterns.addAll(cached);
        }
        return isLineEnd(patterns, stationName);
    }

    /**
     * 어떤 패턴의 첫·끝 정차이고 <b>어느 패턴에서도 중간 정차가 아닌</b> 역이 노선 끝이다. 중간 회차역(4호선 사당 등)은
     * 다른 패턴의 중간 정차라 끝이 아니다. 순환 패턴(2호선 본선)은 첫·끝이 없다 — 전부 중간 정차로 센다. 어느 패턴에도
     * 없는 역은 모른다.
     */
    static Optional<Boolean> isLineEnd(List<DirectedRailPattern> patterns, String stationName) {
        String station = SubwayPatternStationMatcher.displayName(stationName);
        if (patterns.isEmpty() || station == null) {
            return Optional.empty();
        }
        Boolean end = null;
        for (DirectedRailPattern pattern : patterns) {
            List<RailPattern.Stop> stops = pattern.stops();
            for (int i = 0; i < stops.size(); i++) {
                if (!station.equals(SubwayPatternStationMatcher.displayName(stops.get(i).name()))) {
                    continue;
                }
                boolean edge = i == 0 || i == stops.size() - 1;
                if (pattern.loop() || !edge) {
                    return Optional.of(false);
                }
                end = true;
            }
        }
        return Optional.ofNullable(end);
    }
}
