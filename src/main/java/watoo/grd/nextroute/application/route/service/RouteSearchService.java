package watoo.grd.nextroute.application.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.port.in.SearchRouteUseCase;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.domain.route.log.entity.RouteSearchLog;
import watoo.grd.nextroute.domain.route.log.service.RouteDataService;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RouteSearchService implements SearchRouteUseCase {

    private static final int TRAFFIC_TYPE_WALK = 3;

    private final OdSayApiPort odSayApiPort;
    private final RouteDataService routeDataService;
    private final ObjectMapper objectMapper;
    private final RoutePolylineEnricher polylineEnricher;
    private final WalkSegmentEnricher walkSegmentEnricher;

    @Override
    public RouteSearchResult search(RouteSearchRequest request) {
        RouteSearchResult result = odSayApiPort.searchPath(
                request.startX(), request.startY(),
                request.endX(), request.endY()
        );

        // 1. 지하철 노선 폴리라인 보강 (trafficType=1)
        result = polylineEnricher.enrich(result);

        // 2. TMAP 보행자 경로 보강 (trafficType=3)
        result = walkSegmentEnricher.enrich(result,
                request.startX(), request.startY(), request.startName(),
                request.endX(), request.endY(), request.endName());

        // 3. 로그 저장 (도보 polyline/walkSteps는 제외 — 로그 사이즈 폭증 방지)
        saveLog(request, result);

        return result;
    }

    private void saveLog(RouteSearchRequest request, RouteSearchResult result) {
        try {
            RouteSearchResult stripped = stripWalkDetailsForLog(result);
            String responseJson = objectMapper.writeValueAsString(stripped);
            RouteSearchLog searchLog = RouteSearchLog.builder()
                    .startX(request.startX())
                    .startY(request.startY())
                    .endX(request.endX())
                    .endY(request.endY())
                    .startName(request.startName())
                    .endName(request.endName())
                    .pathCount(result.paths() != null ? result.paths().size() : 0)
                    .searchType(result.searchType())
                    .responseJson(responseJson)
                    .searchedAt(LocalDateTime.now())
                    .build();
            routeDataService.save(searchLog);
        } catch (Exception e) {
            log.warn("[Route] Failed to save search log: {}", e.getMessage());
        }
    }

    /**
     * 로그에 저장할 응답에서 도보 polyline/walkSteps를 제거한다.
     * 클라이언트 응답은 보강된 상태 그대로 유지하고, 로그만 축소.
     */
    private RouteSearchResult stripWalkDetailsForLog(RouteSearchResult result) {
        if (result.paths() == null || result.paths().isEmpty()) {
            return result;
        }
        List<PathResult> strippedPaths = result.paths().stream()
                .map(this::stripPath)
                .toList();
        return new RouteSearchResult(
                result.searchType(), result.busCount(), result.subwayCount(),
                result.trainCount(), result.airCount(), strippedPaths);
    }

    private PathResult stripPath(PathResult path) {
        if (path.subPaths() == null || path.subPaths().isEmpty()) {
            return path;
        }
        boolean anyWalk = path.subPaths().stream().anyMatch(sp -> sp.trafficType() == TRAFFIC_TYPE_WALK);
        if (!anyWalk) {
            return path;
        }
        List<SubPathResult> strippedSubs = path.subPaths().stream()
                .map(sp -> sp.trafficType() == TRAFFIC_TYPE_WALK ? stripWalkSubPath(sp) : sp)
                .toList();
        return new PathResult(path.pathType(), path.info(), strippedSubs, path.laneGraphics());
    }

    private SubPathResult stripWalkSubPath(SubPathResult sp) {
        return new SubPathResult(
                sp.trafficType(), sp.sectionTime(), sp.distance(),
                sp.lanes(), sp.stations(),
                sp.startName(), sp.endName(),
                sp.startX(), sp.startY(), sp.endX(), sp.endY(),
                sp.trainType(), sp.payment(), sp.startId(), sp.way(), sp.wayCode(),
                null,   // polyline 제외
                sp.startExitNo(), sp.startExitX(), sp.startExitY(),
                sp.endExitNo(),   sp.endExitX(),   sp.endExitY(),
                null    // walkSteps 제외
        );
    }
}
