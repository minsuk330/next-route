package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.mapping.BusRealtimeStopIds;
import watoo.grd.nextroute.domain.journey.repository.JourneyRevisionRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 그 버스 단계의 <b>계획 정류장</b> — 앱이 남은 정류장 수를 세는 바로 그 목록이다(검색 스냅샷의 GTFS 정류장).
 * 승차 정류장이 첫 원소, 하차 정류장이 마지막 원소다. id 는 실시간 API 공간({@link BusRealtimeStopIds})이다.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>경기·TAGO 노선 정류장 표(#267)는 실시간 API 순번 공간이라 GTFS 와 정류장 구성이 다르다. 경기는 경유
 * 가상점이 끼고, TAGO 는 가상점 표시가 없는데도 <b>GTFS 에 없는 실제 정류장</b>이 있다(대전 1001 의
 * 둔곡교차로, 2026-10-07 실측 — 그 정류장에 도착 정보가 나온다). "가상점만 빼고 센다"로는 후자를 못
 * 거른다. 앱과 같은 목록으로 세야 같은 곳을 가리킨다.
 *
 * <p>세션이 아니라 개정본 스냅샷을 읽는다 — 재탐색을 수락하면 개정본만 오른다({@link JourneySubwayStationChainReader}
 * 와 같은 이유).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JourneyPlannedBusStopReader {

    private final JourneyRevisionRepository revisionRepository;
    private final JourneyCompiler compiler;
    private final ObjectMapper objectMapper;
    private final BusRealtimeStopIds busRealtimeStopIds;

    /**
     * 승차부터 하차까지 계획 정류장의 실시간 id. 못 읽으면 빈 목록 — 예외를 던지지 않는다(호출자는 예전
     * 규칙으로 물러난다). 실시간 id 로 못 바꾸는 정류장은 {@code null} 칸으로 남겨 순서를 지킨다.
     */
    public List<String> realtimeStopIds(Long journeyId, int routeRevision, int legIndex) {
        JsonNode snapshot = revisionRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(journeyId, routeRevision)
                .map(revision -> revision.getRouteSnapshot())
                .orElse(null);
        if (snapshot == null || snapshot.isNull()) {
            return List.of();
        }
        try {
            PathResult path = objectMapper.treeToValue(snapshot, PathResult.class);
            JourneyCompiler.Compiled compiled = compiler.compileDetailed(path);
            Integer subPathIndex = compiled.subPathIndexOf(legIndex);
            if (subPathIndex == null || path.subPaths() == null
                    || subPathIndex < 0 || subPathIndex >= path.subPaths().size()) {
                return List.of();
            }
            return ids(path.subPaths().get(subPathIndex));
        } catch (Exception e) {
            log.warn("[RegionalRide] 계획 정류장 읽기 실패 — journeyId={} revision={} legIndex={}: {}",
                    journeyId, routeRevision, legIndex, e.getMessage());
            return List.of();
        }
    }

    private List<String> ids(SubPathResult sub) {
        if (sub == null || sub.stations() == null || sub.stations().size() < 2) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        sub.stations().stream()
                .sorted(Comparator.comparingInt(StationResult::index))
                .forEach(station -> out.add(busRealtimeStopIds.of(station.stationID()).orElse(null)));
        return out;
    }
}
