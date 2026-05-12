package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.dto.LaneGraphicResult;
import watoo.grd.nextroute.application.route.dto.OdsayRoutePolylineData;
import watoo.grd.nextroute.application.route.dto.OdsayRoutePolylinePoint;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.domain.route.polyline.entity.OdsayRouteLineSeed;
import watoo.grd.nextroute.domain.route.polyline.service.OdsayRoutePolylineDataService;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OdsayRoutePolylineLoadService {

    private final OdSayApiPort odSayApiPort;
    private final OdsayRoutePolylineDataService dataService;

    public LoadResult load(String routeId, int laneClass) {
        String mapObject = buildWholeRouteMapObject(routeId, laneClass);
        log.info("[PolylineLoad] Loading routeId={} laneClass={} mapObject={}", routeId, laneClass, mapObject);

        try {
            List<LaneGraphicResult> lanes = odSayApiPort.loadLane(mapObject);
            if (lanes.isEmpty()) {
                log.warn("[PolylineLoad] Empty lanes for routeId={} laneClass={}", routeId, laneClass);
                return LoadResult.failure(routeId, laneClass, "Empty lane response from ODsay");
            }

            OdsayRoutePolylineData data = flatten(lanes);
            if (data.isEmpty()) {
                return LoadResult.failure(routeId, laneClass, "No graphPos points in lane response");
            }

            Integer laneType = lanes.get(0).type();
            dataService.saveOrUpdatePolyline(routeId, laneClass, laneType, data, mapObject);

            log.info("[PolylineLoad] Saved routeId={} laneClass={} pointCount={}",
                    routeId, laneClass, data.size());
            return LoadResult.success(routeId, laneClass, data.size(), mapObject);

        } catch (Exception e) {
            log.error("[PolylineLoad] Failed routeId={} laneClass={}: {}", routeId, laneClass, e.getMessage(), e);
            return LoadResult.failure(routeId, laneClass, e.getMessage());
        }
    }

    public List<LoadResult> loadAll() {
        List<OdsayRouteLineSeed> seeds = dataService.findEnabledSeeds();
        List<LoadResult> results = new ArrayList<>();
        for (OdsayRouteLineSeed seed : seeds) {
            results.add(load(seed.getOdsayRouteId(), seed.getLaneClass()));
        }
        return results;
    }

    private String buildWholeRouteMapObject(String routeId, int laneClass) {
        return "0:0@" + routeId + ":" + laneClass + ":-1:-1";
    }

    /**
     * LaneGraphicResult의 sections → graphPos를 응답 순서대로 flatten하여 0-based absolute index 부여.
     */
    private OdsayRoutePolylineData flatten(List<LaneGraphicResult> lanes) {
        List<OdsayRoutePolylinePoint> points = new ArrayList<>();
        int idx = 0;
        for (LaneGraphicResult lane : lanes) {
            if (lane.sections() == null) continue;
            for (List<CoordPoint> section : lane.sections()) {
                if (section == null) continue;
                for (CoordPoint p : section) {
                    points.add(new OdsayRoutePolylinePoint(idx++, p.x(), p.y()));
                }
            }
        }
        return new OdsayRoutePolylineData(points);
    }

    public record LoadResult(
            String routeId,
            int laneClass,
            boolean loaded,
            int pointCount,
            String sourceMapObject,
            String errorMessage
    ) {
        static LoadResult success(String routeId, int laneClass, int pointCount, String mapObject) {
            return new LoadResult(routeId, laneClass, true, pointCount, mapObject, null);
        }

        static LoadResult failure(String routeId, int laneClass, String error) {
            return new LoadResult(routeId, laneClass, false, 0, null, error);
        }
    }
}
