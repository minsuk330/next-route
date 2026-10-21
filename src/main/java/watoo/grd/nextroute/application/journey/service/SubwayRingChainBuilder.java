package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.subway.service.SubwayStopPatternResolver;
import watoo.grd.nextroute.application.subway.service.SubwayStopPatternResolver.PatternStop;
import watoo.grd.nextroute.application.subway.service.SubwayStopPatternResolver.ResolvedPattern;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind;

import java.util.ArrayList;
import java.util.List;

/**
 * 진행 링의 역 체인을 <b>묶인 열차 기준</b>으로 만든다(subway-express PR-S4). 링 체인의 주입 지점은
 * {@code SubwayRingUpdateService.chainOf} 하나이고, 여기가 그 본체다.
 *
 * <pre>
 * 1. 계획 역 이름 → statnId 체인(예전 그대로, {@link SubwayRingStationResolver})
 * 2. 종류 null·UNKNOWN          → 계획 체인 그대로(예전 동작)
 * 3. 승·하차역 사이 완행 경로     ({@link SubwayStopPatternResolver#allStopPath})
 * 4. NORMAL  → 계획 체인이 완행 경로의 <b>부분수열인데 역이 빠졌으면</b> 완행 경로로 펼친다(OTP 계획이 급행 trip 인데 일반 열차를 탄
 *              경우 — 중간역 관측이 구간 밖으로 보여 하차역 PASSED 로 뛰던 버그). 같거나 어긋나면 계획 그대로
 * 5. EXPRESS → 완행 경로를 열차가 서는 역만 남기고({@link SubwayStopPatternResolver#trim}) 지나치는 역을 다음 정차역의
 *              {@code passingBefore} 에 붙인다. 순번은 정차역만 다시 센다. 탑승·하차역에 서지 않거나 패턴·완행 경로를 모르면 <b>빈
 *              체인</b> — 링을 그리지 않는다(지나치는 역을 정차로 세는 링보다 낫다. 완행 경로는 뒤에서 적재되면 다음 tick 에 선다)
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class SubwayRingChainBuilder {

    private static final SubwayRingChain EMPTY = new SubwayRingChain(List.of());

    private final SubwayRingStationResolver stationResolver;
    private final SubwayStopPatternResolver patternResolver;

    /**
     * @param lineId       노선
     * @param direction    묶인 열차의 방향(도착 API 표기)
     * @param plannedNames 계획 역 이름(승차역 → 하차역)
     * @param kind         묶인 열차 종류. null 은 미분류(예전 행)
     * @param pattern      급행이면 해석된 정차 패턴. 못 해석했으면 null
     */
    public SubwayRingChain build(String lineId, String direction, List<String> plannedNames,
                                 SubwayBindingTrainKind kind, ResolvedPattern pattern) {
        SubwayRingChain planned = stationResolver.chainOf(lineId, plannedNames);
        if (kind == null || kind == SubwayBindingTrainKind.UNKNOWN) {
            return planned;
        }
        boolean express = kind == SubwayBindingTrainKind.EXPRESS;
        if (!planned.isUsable()) {
            return express ? EMPTY : planned;
        }
        String board = planned.at(0).canonicalKey();
        String alight = planned.at(planned.alightingSequence()).canonicalKey();
        if (express && (pattern == null || !pattern.stopsAt(board) || !pattern.stopsAt(alight))) {
            return EMPTY;
        }
        List<String> allStops = board == null || alight == null
                ? List.of() : patternResolver.allStopPath(lineId, direction, board, alight);
        if (allStops.size() < 2) {
            return express ? EMPTY : planned;
        }
        return express ? trimmed(lineId, planned, allStops, pattern) : expanded(lineId, planned, allStops);
    }

    /** 일반 열차: 계획이 완행 경로에서 역을 빠뜨렸을 때만 펼친다. */
    private SubwayRingChain expanded(String lineId, SubwayRingChain planned, List<String> allStops) {
        List<String> keys = planned.stations().stream().map(SubwayRingChain.Station::canonicalKey).toList();
        if (keys.size() >= allStops.size() || keys.contains(null) || !isSubsequence(keys, allStops)) {
            return planned;
        }
        List<SubwayRingChain.Station> stations = new ArrayList<>(allStops.size());
        for (int i = 0; i < allStops.size(); i++) {
            String key = allStops.get(i);
            stations.add(new SubwayRingChain.Station(i, keyOf(key), nameOf(lineId, planned, key)));
        }
        return new SubwayRingChain(stations);
    }

    /** 급행: 서는 역만, 지나치는 역은 다음 정차역 앞에. */
    private SubwayRingChain trimmed(String lineId, SubwayRingChain planned, List<String> allStops,
                                    ResolvedPattern pattern) {
        List<PatternStop> stops = SubwayStopPatternResolver.trim(allStops, pattern);
        if (stops.size() < 2 || !stops.get(stops.size() - 1).stationId().equals(allStops.get(allStops.size() - 1))) {
            return EMPTY;
        }
        List<SubwayRingChain.Station> stations = new ArrayList<>(stops.size());
        for (int i = 0; i < stops.size(); i++) {
            PatternStop stop = stops.get(i);
            stations.add(new SubwayRingChain.Station(i, keyOf(stop.stationId()),
                    nameOf(lineId, planned, stop.stationId()), stop.passingBefore()));
        }
        return new SubwayRingChain(stations, pattern);
    }

    /** 완행 경로 키는 역 id, 역에 못 이은 정차는 {@code name:…} — 그건 체인 키(statnId)가 아니다. */
    private static String keyOf(String pathKey) {
        return pathKey == null || pathKey.startsWith("name:") ? null : pathKey;
    }

    /** 계획에 있던 역이면 계획 이름(표기를 지킨다), 아니면 역 마스터 이름. */
    private String nameOf(String lineId, SubwayRingChain planned, String key) {
        int at = planned.sequenceOfKey(key);
        if (at >= 0) {
            return planned.at(at).name();
        }
        if (key != null && key.startsWith("name:")) {
            return key.substring("name:".length());
        }
        String name = stationResolver.nameOf(lineId, key);
        return name != null ? name : key;
    }

    static boolean isSubsequence(List<String> part, List<String> whole) {
        int from = 0;
        for (String key : part) {
            int at = whole.subList(from, whole.size()).indexOf(key);
            if (at < 0) {
                return false;
            }
            from += at + 1;
        }
        return true;
    }
}
