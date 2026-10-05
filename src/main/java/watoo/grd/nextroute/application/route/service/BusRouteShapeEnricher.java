package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineSection;
import watoo.grd.nextroute.application.bus.port.out.BusRouteSegmentPolylinePort;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.dto.LaneResult;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.domain.bus.service.BusRoutePolylineData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 검색 응답의 버스 구간 폴리라인을 여정 안내가 그리는 좌표로 바꾼다.
 *
 * <p>안내 지도({@code JourneyNavigationGeometryService})는 노선·승하차 순번이 해석된 버스 구간을
 * {@code bus_route_segment_polyline} 의 정류장 구간 좌표로 그린다. 검색 응답은 엔진 형상(OTP shape,
 * 없으면 정류장 직선)을 싣기 때문에 "지도에서 보기"와 안내 화면의 선이 달랐다. 여기서 같은 원천으로
 * 맞춘다.
 *
 * <p>해석 규칙은 여정 시작 때({@code JourneyCompiler} → {@code JourneyTransitIdEnricher})와 같다.
 * 첫 lane 의 원천 노선 id(없으면 버스 번호)로 노선을, 구간 양 끝 원천 정류장 id 로 정류장을 찾고,
 * 순번이 한 개로 정해질 때만 쓴다. 하나라도 어긋나면 원래 폴리라인을 그대로 둔다 — 안내도 그때는
 * 검색 결과 좌표를 쓰므로 두 지도는 여전히 같다.
 *
 * <p>응답을 내리기 전에 실행되므로 검색 스냅샷에도 이 좌표가 들어간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusRouteShapeEnricher {

    private static final int TRAFFIC_BUS = 2;

    private final TransferStopResolver resolver;
    private final BusRouteSegmentPolylinePort polylinePort;

    public RouteSearchResult enrich(RouteSearchResult result) {
        if (result == null || result.paths() == null || result.paths().isEmpty()) {
            return result;
        }
        Lookup lookup = new Lookup();
        List<PathResult> paths = result.paths().stream()
                .map(path -> enrichPath(path, lookup))
                .toList();
        return new RouteSearchResult(result.searchType(), result.busCount(), result.subwayCount(),
                result.trainCount(), result.airCount(), paths, result.meta(),
                result.searchSessionId(), result.expiresAt());
    }

    private PathResult enrichPath(PathResult path, Lookup lookup) {
        if (path.subPaths() == null || path.subPaths().stream().noneMatch(this::isBus)) {
            return path;
        }
        List<SubPathResult> subPaths = path.subPaths().stream()
                .map(sub -> isBus(sub) ? enrichBus(sub, lookup) : sub)
                .toList();
        return new PathResult(path.pathKey(), path.pathType(), path.info(), subPaths,
                path.laneGraphics());
    }

    private boolean isBus(SubPathResult sub) {
        return sub != null && sub.trafficType() == TRAFFIC_BUS;
    }

    private SubPathResult enrichBus(SubPathResult sub, Lookup lookup) {
        try {
            List<CoordPoint> shape = routeShape(sub, lookup);
            return shape.size() >= 2 ? sub.withPolyline(shape) : sub;
        } catch (RuntimeException e) {
            // 형상 교체는 표시 품질 보강이다. 실패해도 검색은 원래 좌표로 응답한다.
            log.warn("[BusShape] 버스 구간 형상 교체 실패 — 원래 폴리라인 유지", e);
            return sub;
        }
    }

    private List<CoordPoint> routeShape(SubPathResult sub, Lookup lookup) {
        if (sub.lanes() == null || sub.lanes().isEmpty()) {
            return List.of();
        }
        LaneResult lane = sub.lanes().get(0);
        Optional<String> routeId = lookup.routeId(sourceRouteId(lane), routeName(lane));
        if (routeId.isEmpty()) {
            return List.of();
        }
        Integer boardingSeq = lookup.seq(routeId.get(), sourceStopId(sub, true));
        Integer alightingSeq = lookup.seq(routeId.get(), sourceStopId(sub, false));
        if (boardingSeq == null || alightingSeq == null || boardingSeq >= alightingSeq) {
            return List.of();
        }
        return merge(polylinePort.findSections(routeId.get(), boardingSeq, alightingSeq));
    }

    /** 안내 지도의 {@code assemble} 과 같은 방식 — 구간 좌표를 잇고 이음매의 중복 점만 뺀다. */
    private List<CoordPoint> merge(List<BusRouteSegmentPolylineSection> sections) {
        List<CoordPoint> merged = new ArrayList<>();
        for (BusRouteSegmentPolylineSection section : sections) {
            if (section.points() == null) {
                continue;
            }
            for (BusRoutePolylineData.Point point : section.points()) {
                CoordPoint next = new CoordPoint(point.x(), point.y());
                if (merged.isEmpty() || !merged.get(merged.size() - 1).equals(next)) {
                    merged.add(next);
                }
            }
        }
        return merged;
    }

    // ── JourneyCompiler 와 같은 원천 식별자 규칙 ─────────────────────────────

    private String sourceRouteId(LaneResult lane) {
        if (hasText(lane.busLocalBlID())) {
            return lane.busLocalBlID();
        }
        return lane.busID() == null ? null : String.valueOf(lane.busID());
    }

    private String routeName(LaneResult lane) {
        return hasText(lane.busNo()) ? lane.busNo() : lane.name();
    }

    private String sourceStopId(SubPathResult sub, boolean start) {
        String local = start ? sub.startLocalStationID() : sub.endLocalStationID();
        if (hasText(local)) {
            return local;
        }
        List<StationResult> stations = sub.stations();
        if (stations == null || stations.isEmpty()) {
            return null;
        }
        StationResult station = start ? stations.get(0) : stations.get(stations.size() - 1);
        return hasText(station.stationID()) ? station.stationID() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 한 검색 안에서 같은 노선·정류장 조회를 반복하지 않는다. 경로마다 같은 버스가 자주 겹친다. */
    private final class Lookup {
        private final Map<String, Optional<String>> routes = new HashMap<>();
        private final Map<String, Optional<String>> stops = new HashMap<>();
        private final Map<String, Optional<Integer>> seqs = new HashMap<>();

        Optional<String> routeId(String sourceRouteId, String routeName) {
            return routes.computeIfAbsent(sourceRouteId + "|" + routeName,
                    key -> resolver.resolveRoute(sourceRouteId, routeName)
                            .map(TransferStopResolver.RouteResolution::routeId));
        }

        Integer seq(String routeId, String sourceStopId) {
            if (!hasText(sourceStopId)) {
                return null;
            }
            Optional<String> stopId = stops.computeIfAbsent(sourceStopId,
                    key -> resolver.resolveStop(key).map(TransferStopResolver.StopResolution::stopId));
            if (stopId.isEmpty()) {
                return null;
            }
            return seqs.computeIfAbsent(routeId + "|" + stopId.get(), key -> {
                TransferStopResolver.SeqResolution seq = resolver.resolveSeq(routeId, stopId.get());
                return seq.isSingle() ? Optional.of(seq.candidates().get(0)) : Optional.empty();
            }).orElse(null);
        }
    }
}
