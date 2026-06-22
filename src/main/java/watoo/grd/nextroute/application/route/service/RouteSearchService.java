package watoo.grd.nextroute.application.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.dto.WalkSegment;
import watoo.grd.nextroute.application.route.exception.OdSayApiException;
import watoo.grd.nextroute.application.route.exception.TmapApiException;
import watoo.grd.nextroute.application.route.port.in.SearchRouteUseCase;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.port.out.TmapPedestrianPort;
import watoo.grd.nextroute.application.route.port.out.TmapPedestrianPort.WalkSearchCommand;
import watoo.grd.nextroute.domain.route.log.entity.RouteSearchLog;
import watoo.grd.nextroute.domain.route.log.service.RouteDataService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RouteSearchService implements SearchRouteUseCase {

    private static final int TRAFFIC_TYPE_WALK = 3;
    /** ODsay 에러코드: 출/도착지가 700m 이내. 이 경우 TMAP 도보 경로로만 응답한다. */
    private static final int ODSAY_CODE_WITHIN_WALK_DISTANCE = -98;
    /** 도보 전용 응답의 pathType (ODsay pathType=3 재사용, subPath는 전부 trafficType=3). */
    private static final int PATH_TYPE_WALK_ONLY = 3;

    private final OdSayApiPort odSayApiPort;
    private final TmapPedestrianPort tmapPort;
    private final RouteDataService routeDataService;
    private final ObjectMapper objectMapper;
    private final RoutePolylineEnricher polylineEnricher;
    private final WalkSegmentEnricher walkSegmentEnricher;
    private final TransferArrivalEnricher transferArrivalEnricher;

    @Override
    public RouteSearchResult search(RouteSearchRequest request) {
        Instant searchStartedAt = Instant.now();

        RouteSearchResult result;
        try {
            result = odSayApiPort.searchPath(
                    request.startX(), request.startY(),
                    request.endX(), request.endY()
            );
        } catch (OdSayApiException e) {
            if (e.getErrorCode() == ODSAY_CODE_WITHIN_WALK_DISTANCE) {
                // 출/도착지 700m 이내 → ODsay 대중교통 경로 없음. TMAP 도보 경로로만 응답.
                return walkOnlyFallback(request, e);
            }
            throw e;
        }

        // 1. 지하철 노선 폴리라인 보강 (trafficType=1)
        result = polylineEnricher.enrich(result);

        // 2. TMAP 보행자 경로 보강 (trafficType=3)
        result = walkSegmentEnricher.enrich(result,
                request.startX(), request.startY(), request.startName(),
                request.endX(), request.endY(), request.endName());

        // 3. 환승 도착예측 보강 (trafficType=2 버스)
        result = transferArrivalEnricher.enrich(result, searchStartedAt);

        // 4. 로그 저장 (도보 polyline/walkSteps는 제외 — 로그 사이즈 폭증 방지)
        saveLog(request, result);

        return result;
    }

    /**
     * ODsay -98(출/도착지 700m 이내) 대응: TMAP 보행자 경로만으로 응답을 구성한다.
     * TMAP이 빈 결과(서비스지역 외)이거나 호출 실패면 원래 -98 에러를 그대로 전파한다.
     */
    private RouteSearchResult walkOnlyFallback(RouteSearchRequest request, OdSayApiException cause) {
        WalkSegment segment;
        try {
            segment = tmapPort.search(new WalkSearchCommand(
                    request.startX(), request.startY(),
                    request.endX(), request.endY(),
                    request.startName(), request.endName()));
        } catch (TmapApiException te) {
            log.warn("[Route] -98 fallback TMAP 실패 — 원래 ODsay 에러 전파: {}", te.getMessage());
            throw cause;
        }
        if (segment.isEmpty()) {
            log.warn("[Route] -98 fallback TMAP 빈 응답(서비스지역 외) — 원래 ODsay 에러 전파");
            throw cause;
        }

        RouteSearchResult result = buildWalkOnlyResult(request, segment);
        saveLog(request, result);
        return result;
    }

    private RouteSearchResult buildWalkOnlyResult(RouteSearchRequest request, WalkSegment segment) {
        int totalTimeMin = Math.max(1, (int) Math.round(segment.totalTime() / 60.0));
        Integer walkSeconds = segment.totalTime() > 0 ? segment.totalTime() : null;

        SubPathResult walk = new SubPathResult(
                TRAFFIC_TYPE_WALK, totalTimeMin, (double) segment.totalDistance(),
                List.of(), List.of(),
                request.startName(), request.endName(),
                request.startX(), request.startY(), request.endX(), request.endY(),
                null, null,            // trainType, payment
                null, null, null,      // startId, way, wayCode (지하철 전용)
                segment.polyline(),
                null, null, null, null, null, null,  // 지하철 출구 정보
                segment.steps(),
                null, null, null, null, null,        // ODsay 버스 식별자
                walkSeconds,
                null                   // transferArrivals
        );
        PathInfo info = new PathInfo(
                totalTimeMin, 0, segment.totalDistance(), 0,
                request.startName(), request.endName(), null);
        PathResult path = new PathResult(PATH_TYPE_WALK_ONLY, info, List.of(walk), List.of());
        return new RouteSearchResult(0, 0, 0, 0, 0, List.of(path));
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
                null,   // walkSteps 제외
                sp.startLocalStationID(), sp.endLocalStationID(),
                sp.startArsID(), sp.endArsID(), sp.endID(),
                sp.walkTotalTimeSeconds(), sp.transferArrivals()
        );
    }
}
