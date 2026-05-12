package watoo.grd.nextroute.infrastructure.adapter.out.api.tmap;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.route.config.TmapProperties;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.dto.WalkSegment;
import watoo.grd.nextroute.application.route.dto.WalkStep;
import watoo.grd.nextroute.application.route.exception.TmapApiException;
import watoo.grd.nextroute.application.route.port.out.TmapPedestrianPort;
import watoo.grd.nextroute.infrastructure.adapter.out.api.tmap.dto.TmapFeature;
import watoo.grd.nextroute.infrastructure.adapter.out.api.tmap.dto.TmapFeatureProperties;
import watoo.grd.nextroute.infrastructure.adapter.out.api.tmap.dto.TmapGeometry;
import watoo.grd.nextroute.infrastructure.adapter.out.api.tmap.dto.TmapPedestrianRequest;
import watoo.grd.nextroute.infrastructure.adapter.out.api.tmap.dto.TmapPedestrianResponse;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TmapPedestrianAdapter implements TmapPedestrianPort {

    private static final String PATH = "/tmap/routes/pedestrian";
    private static final long RETRY_BACKOFF_MS = 200L;

    private final RestClient restClient;
    private final TmapProperties properties;

    @Override
    public WalkSegment search(WalkSearchCommand cmd) {
        TmapPedestrianRequest body = TmapPedestrianRequest.of(
                cmd.startX(), cmd.startY(),
                cmd.endX(), cmd.endY(),
                cmd.startName(), cmd.endName(),
                cmd.searchOption()
        );

        try {
            return callApi(body);
        } catch (TmapApiException e) {
            if (!properties.isRetryOnFailure()) throw e;
            log.warn("[TMAP] retry after {}ms: {}", RETRY_BACKOFF_MS, e.getMessage());
            try {
                Thread.sleep(RETRY_BACKOFF_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new TmapApiException(-1, "Interrupted during retry", ie);
            }
            return callApi(body);
        }
    }

    private WalkSegment callApi(TmapPedestrianRequest body) {
        TmapPedestrianResponse response;
        try {
            response = restClient.post()
                    .uri(properties.getBaseUrl() + PATH + "?version=1")
                    .header("appKey", properties.getAppKey())
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new TmapApiException(res.getStatusCode().value(),
                                "TMAP 5xx: " + res.getStatusText());
                    })
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new TmapApiException(res.getStatusCode().value(),
                                "TMAP 4xx: " + res.getStatusText());
                    })
                    .body(TmapPedestrianResponse.class);
        } catch (HttpStatusCodeException e) {
            throw new TmapApiException(e.getStatusCode().value(),
                    "TMAP HTTP error: " + e.getMessage(), e);
        } catch (ResourceAccessException e) {
            throw new TmapApiException(-1, "TMAP I/O timeout: " + e.getMessage(), e);
        } catch (TmapApiException e) {
            throw e;
        } catch (Exception e) {
            throw new TmapApiException(-1, "TMAP unexpected: " + e.getMessage(), e);
        }

        if (response == null || response.getFeatures() == null || response.getFeatures().isEmpty()) {
            log.info("[TMAP] empty features (out of service area?)");
            return WalkSegment.empty();
        }

        return toWalkSegment(response);
    }

    private WalkSegment toWalkSegment(TmapPedestrianResponse response) {
        List<CoordPoint> polyline = new ArrayList<>();
        List<WalkStep> steps = new ArrayList<>();
        int totalDistance = 0;
        int totalTime = 0;

        for (TmapFeature feature : response.getFeatures()) {
            TmapGeometry geom = feature.getGeometry();
            TmapFeatureProperties props = feature.getProperties();
            if (geom == null || geom.getType() == null) continue;

            switch (geom.getType()) {
                case "LineString" -> appendLineString(polyline, geom.getCoordinates());
                case "Point" -> {
                    appendPointToPolyline(polyline, geom.getCoordinates());
                    if (props != null && "SP".equals(props.getPointType())) {
                        if (props.getTotalDistance() != null) totalDistance = props.getTotalDistance();
                        if (props.getTotalTime() != null)     totalTime     = props.getTotalTime();
                    }
                    if (props != null) {
                        steps.add(toWalkStep(props, geom.getCoordinates()));
                    }
                }
                default -> log.debug("[TMAP] unknown geometry type: {}", geom.getType());
            }
        }

        return new WalkSegment(List.copyOf(polyline), totalDistance, totalTime, List.copyOf(steps));
    }

    private void appendLineString(List<CoordPoint> polyline, JsonNode coords) {
        if (coords == null || !coords.isArray()) return;
        for (JsonNode pair : coords) {
            CoordPoint pt = toCoordPoint(pair);
            if (pt != null) appendIfNotDuplicate(polyline, pt);
        }
    }

    private void appendPointToPolyline(List<CoordPoint> polyline, JsonNode coords) {
        CoordPoint pt = toCoordPoint(coords);
        if (pt != null) appendIfNotDuplicate(polyline, pt);
    }

    private void appendIfNotDuplicate(List<CoordPoint> polyline, CoordPoint pt) {
        if (polyline.isEmpty()) {
            polyline.add(pt);
            return;
        }
        CoordPoint last = polyline.get(polyline.size() - 1);
        if (last.x() != pt.x() || last.y() != pt.y()) {
            polyline.add(pt);
        }
    }

    /** TMAP GeoJSON 좌표는 [lng, lat] 순서. 내부 (x, y) = (lng, lat) 컨벤션 그대로 매핑. */
    private CoordPoint toCoordPoint(JsonNode pair) {
        if (pair == null || !pair.isArray() || pair.size() < 2) return null;
        double x = pair.get(0).asDouble();
        double y = pair.get(1).asDouble();
        return new CoordPoint(x, y);
    }

    private WalkStep toWalkStep(TmapFeatureProperties props, JsonNode pointCoord) {
        double x = 0, y = 0;
        if (pointCoord != null && pointCoord.isArray() && pointCoord.size() >= 2) {
            x = pointCoord.get(0).asDouble();
            y = pointCoord.get(1).asDouble();
        }
        return new WalkStep(
                props.getIndex() != null ? props.getIndex() : 0,
                props.getPointType(),
                props.getTurnType(),
                props.getDescription(),
                x, y
        );
    }
}
