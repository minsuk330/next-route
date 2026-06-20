package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.route.dto.*;
import watoo.grd.nextroute.domain.route.polyline.service.OdsayRoutePolylineDataService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 지하철 subPath(trafficType=1)에 DB 캐시된 폴리라인을 주입한다.
 * DB miss 시 collection job을 등록하고 빈 polyline을 반환한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutePolylineEnricher {

    private final OdsayMapObjParser mapObjParser;
    private final OdsayRoutePolylineSlicer slicer;
    private final OdsayRoutePolylineDataService dataService;

    public RouteSearchResult enrich(RouteSearchResult original) {
        List<PathResult> enrichedPaths = original.paths().stream()
                .map(this::enrichPath)
                .toList();

        return new RouteSearchResult(
                original.searchType(),
                original.busCount(),
                original.subwayCount(),
                original.trainCount(),
                original.airCount(),
                enrichedPaths
        );
    }

    private PathResult enrichPath(PathResult path) {
        String mapObj = path.info() != null ? extractMapObj(path.info()) : null;
        if (mapObj == null || mapObj.isBlank()) {
            return path;
        }

        List<OdsayMapObjFragment> fragments = mapObjParser.parse(mapObj);
        // laneClass=2(지하철)인 fragment만 처리
        List<OdsayMapObjFragment> subwayFragments = fragments.stream()
                .filter(OdsayMapObjFragment::isSubway)
                .toList();

        if (subwayFragments.isEmpty()) {
            return path;
        }

        // routeId 별 폴리라인 일괄 조회 (중복 DB 호출 방지)
        Map<String, OdsayRoutePolylineData> polylineCache = loadPolylines(subwayFragments, mapObj);

        // trafficType=1 subPath 순서와 subway fragment 순서 대응
        List<SubPathResult> subwaySubPaths = path.subPaths().stream()
                .filter(sp -> sp.trafficType() == 1)
                .toList();

        int matchCount = Math.min(subwaySubPaths.size(), subwayFragments.size());

        // 순서 매칭: 검색 결과 내 지하철 subPath 순서 = mapObj fragment 순서
        // (fragment의 routeId/laneClass만 DB 조회에 사용. start/end 값은 stationId 성격이라 index로 쓰지 않음)
        List<SubPathResult> enrichedSubPaths = path.subPaths().stream()
                .map(sp -> {
                    if (sp.trafficType() != 1) return sp;
                    int idx = subwaySubPaths.indexOf(sp);
                    if (idx < 0 || idx >= matchCount) return sp;

                    OdsayMapObjFragment fragment = subwayFragments.get(idx);
                    OdsayRoutePolylineData data = polylineCache.get(fragment.odsayRouteId());
                    // DB miss: enrichment 시도했지만 데이터 없음 → 빈 polyline 으로 명시
                    if (data == null) return withPolyline(sp, List.of());

                    // 좌표 누락: subPath 자체 데이터 부족 → 빈 polyline 으로 명시
                    if (sp.startX() == null || sp.startY() == null
                            || sp.endX() == null || sp.endY() == null) {
                        log.warn("[PolylineEnricher] Missing subPath coord routeId={} subPath={}->{} startX={} startY={} endX={} endY={}",
                                fragment.odsayRouteId(), sp.startName(), sp.endName(),
                                sp.startX(), sp.startY(), sp.endX(), sp.endY());
                        return withPolyline(sp, List.of());
                    }

                    StationResult nextStation = nextStation(sp);
                    List<CoordPoint> polyline = slicer.sliceByCoordinates(
                            data,
                            sp.startX(), sp.startY(),
                            sp.endX(),   sp.endY(),
                            nextStation != null ? nextStation.x() : null,
                            nextStation != null ? nextStation.y() : null
                    );

                    if (polyline.isEmpty()) {
                        log.warn("[PolylineEnricher] Empty slice for routeId={} cachedSize={} subPath={}->{} startXY=({},{}) endXY=({},{})",
                                fragment.odsayRouteId(), data.size(),
                                sp.startName(), sp.endName(),
                                sp.startX(), sp.startY(), sp.endX(), sp.endY());
                    }

                    return withPolyline(sp, polyline);
                })
                .toList();

        return new PathResult(path.pathType(), path.info(), enrichedSubPaths, path.laneGraphics());
    }

    private Map<String, OdsayRoutePolylineData> loadPolylines(
            List<OdsayMapObjFragment> fragments, String rawMapObj) {

        Map<String, OdsayRoutePolylineData> result = new HashMap<>();
        fragments.stream()
                .map(OdsayMapObjFragment::odsayRouteId)
                .distinct()
                .forEach(routeId -> {
                    Optional<OdsayRoutePolylineData> data = dataService.findPolyline(routeId, 2);
                    if (data.isPresent()) {
                        result.put(routeId, data.get());
                    } else {
                        log.info("[PolylineEnricher] Cache miss routeId={}, enqueuing collection job", routeId);
                        dataService.requestCollection(routeId, 2, rawMapObj);
                    }
                });
        return result;
    }

    private String extractMapObj(PathInfo info) {
        return info.mapObj();
    }

    private StationResult nextStation(SubPathResult sp) {
        if (sp.stations() == null || sp.stations().size() < 2) {
            return null;
        }
        StationResult station = sp.stations().get(1);
        if (station.x() == null || station.y() == null) {
            return null;
        }
        return station;
    }

    private SubPathResult withPolyline(SubPathResult sp, List<CoordPoint> polyline) {
        return new SubPathResult(
                sp.trafficType(), sp.sectionTime(), sp.distance(),
                sp.lanes(), sp.stations(), sp.startName(), sp.endName(),
                sp.startX(), sp.startY(), sp.endX(), sp.endY(),
                sp.trainType(), sp.payment(), sp.startId(), sp.way(), sp.wayCode(),
                polyline,
                sp.startExitNo(), sp.startExitX(), sp.startExitY(),
                sp.endExitNo(),   sp.endExitX(),   sp.endExitY(),
                sp.walkSteps(),
                sp.startLocalStationID(), sp.endLocalStationID(),
                sp.startArsID(), sp.endArsID(), sp.endID(),
                sp.walkTotalTimeSeconds(), sp.transferArrivals()
        );
    }
}
