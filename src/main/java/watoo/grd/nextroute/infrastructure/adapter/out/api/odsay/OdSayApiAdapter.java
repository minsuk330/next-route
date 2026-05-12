package watoo.grd.nextroute.infrastructure.adapter.out.api.odsay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import watoo.grd.nextroute.application.route.config.OdSayProperties;
import watoo.grd.nextroute.application.route.dto.*;
import watoo.grd.nextroute.application.route.exception.OdSayApiException;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.infrastructure.adapter.out.api.odsay.dto.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OdSayApiAdapter implements OdSayApiPort {

    private static final int MAX_RETRY = 3;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final OdSayProperties odSayProperties;

    @Override
    public RouteSearchResult searchPath(double sx, double sy, double ex, double ey) {
        URI uri = UriComponentsBuilder.fromHttpUrl(odSayProperties.getBaseUrl() + "/searchPubTransPathT")
                .queryParam("apiKey", odSayProperties.getKey())
                .queryParam("SX", sx)
                .queryParam("SY", sy)
                .queryParam("EX", ex)
                .queryParam("EY", ey)
                .queryParam("OPT", 0)
                .build(true)
                .toUri();
      log.info("[OdSay] search url={}", uri);

        OdSaySearchResponse response = callApi(uri);

        if (response.getError() != null && !response.getError().isNull()) {
            JsonNode errorNode = response.getError();
            JsonNode first = errorNode.isArray() ? errorNode.get(0) : errorNode;
            String codeStr = first.has("code") ? first.get("code").asText() : "-1";
            String msg = first.has("message") ? first.get("message").asText()
                       : first.has("msg") ? first.get("msg").asText()
                       : "Unknown OdSay error";
            int code;
            try { code = Integer.parseInt(codeStr); } catch (Exception e) { code = -1; }
            log.error("[OdSay] API error: code={}, msg={}", code, msg);
            throw new OdSayApiException(code, msg);
        }

        if (response.getResult() == null) {
            throw new OdSayApiException(-1, "OdSay API returned null result");
        }

        return toRouteSearchResult(response.getResult());
    }

    private OdSaySearchResponse callApi(URI uri) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                String json = restTemplate.getForObject(uri, String.class);
                if (json == null) {
                    log.warn("[OdSay] Null response on attempt {}/{}", attempt, MAX_RETRY);
                } else {
                    return objectMapper.readValue(json, OdSaySearchResponse.class);
                }
            } catch (Exception e) {
                log.warn("[OdSay] API call failed on attempt {}/{}: {}", attempt, MAX_RETRY, e.getMessage(), e);
                if (attempt == MAX_RETRY) {
                    throw new OdSayApiException(-1, "OdSay API call failed after " + MAX_RETRY + " attempts: " + e.getMessage());
                }
            }

            try {
                long sleepMs = (long) Math.pow(2, attempt) * 1000;
                Thread.sleep(sleepMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new OdSayApiException(-1, "Interrupted during retry");
            }
        }

        throw new OdSayApiException(-1, "OdSay API call failed after " + MAX_RETRY + " attempts");
    }

    private RouteSearchResult toRouteSearchResult(OdSayResult result) {
        List<PathResult> paths = result.getPath() != null
                ? result.getPath().stream().map(this::toPathResult).toList()
                : Collections.emptyList();

        return new RouteSearchResult(
                result.getSearchType() != null ? result.getSearchType() : 0,
                result.getBusCount() != null ? result.getBusCount() : 0,
                result.getSubwayCount() != null ? result.getSubwayCount() : 0,
                result.getTrainCount() != null ? result.getTrainCount() : 0,
                result.getAirCount() != null ? result.getAirCount() : 0,
                paths
        );
    }


    private PathResult toPathResult(OdSayPath path) {

        List<SubPathResult> subPaths = path.getSubPath() != null
                ? path.getSubPath().stream().map(this::toSubPathResult).toList()
                : Collections.emptyList();

        return new PathResult(
                path.getPathType() != null ? path.getPathType() : 0,
                toPathInfo(path.getInfo()),
                subPaths,
                Collections.emptyList()
        );
    }

    @Override
    public List<LaneGraphicResult> loadLane(String mapObj) {
        if (mapObj == null || mapObj.isBlank()) {
            return Collections.emptyList();
        }

        String loadLaneMapObject = toLoadLaneMapObject(mapObj);
        // apiKey는 URL 인코딩 필수 (키에 '/' 등 특수문자 포함 가능)
        // mapObject는 URI.create()로 '@', ':' 그대로 전달 (UriComponentsBuilder는 이를 인코딩함)
        String encodedApiKey = URLEncoder.encode(odSayProperties.getKey(), StandardCharsets.UTF_8);
        String rawUrl = odSayProperties.getBaseUrl() + "/loadLane"
                + "?apiKey=" + encodedApiKey
                + "&mapObject=" + loadLaneMapObject;

        try {
            URI uri = URI.create(rawUrl);
            String json = restTemplate.getForObject(uri, String.class);
            if (json == null) return Collections.emptyList();
            OdSayLoadLaneResponse response = objectMapper.readValue(json, OdSayLoadLaneResponse.class);

            if (response.getError() != null && !response.getError().isNull()) {
                JsonNode errorNode = response.getError();
                JsonNode first = errorNode.isArray() ? errorNode.get(0) : errorNode;
                String codeStr = first.has("code") ? first.get("code").asText() : "-1";
                String msg = first.has("message") ? first.get("message").asText() : "Unknown ODsay error";
                int code;
                try { code = Integer.parseInt(codeStr); } catch (Exception ex) { code = -1; }
                log.error("[OdSay] loadLane error: code={} msg={} mapObj={}", code, msg, loadLaneMapObject);
                throw new OdSayApiException(code, msg);
            }

            if (response.getResult() == null || response.getResult().getLane() == null) {
                return Collections.emptyList();
            }
            double[] base = parseBase(loadLaneMapObject);
            return response.getResult().getLane().stream()
                    .map(lane -> toLaneGraphicResult(lane, base[0], base[1]))
                    .toList();
        } catch (OdSayApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[OdSay] loadLane failed for mapObj={}: {}", loadLaneMapObject, e.getMessage());
            return Collections.emptyList();
        }
    }

    private String toLoadLaneMapObject(String mapObj) {
        String trimmed = mapObj.trim();
        String firstPart = trimmed.split("@", 2)[0];
        String[] firstPartTokens = firstPart.split(":");
        if (firstPartTokens.length == 2) {
            return trimmed;
        }

        return "0:0@" + trimmed;
    }

    /** mapObj에서 BaseX, BaseY 추출. 형식: BaseX:BaseY@... */
    private double[] parseBase(String mapObj) {
        try {
            String basePart = mapObj.split("@")[0];
            String[] parts = basePart.split(":");
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (Exception e) {
            return new double[]{0, 0};
        }
    }

    private LaneGraphicResult toLaneGraphicResult(OdSayLaneGraphic lane, double baseX, double baseY) {
        List<List<CoordPoint>> sections = Collections.emptyList();
        if (lane.getSection() != null) {
            sections = lane.getSection().stream()
                    .map(section -> {
                        if (section.getGraphPos() == null) return List.<CoordPoint>of();
                        return section.getGraphPos().stream()
                                .filter(p -> p.getX() != null && p.getY() != null)
                                .map(p -> new CoordPoint(baseX + p.getX(), baseY + p.getY()))
                                .toList();
                    })
                    .toList();
        }
        return new LaneGraphicResult(
                lane.getLaneClass() != null ? lane.getLaneClass() : 0,
                lane.getType() != null ? lane.getType() : 0,
                sections
        );
    }

    private PathInfo toPathInfo(OdSayPathInfo info) {
        if (info == null) {
            return new PathInfo(0, 0, 0, 0, "", "", null);
        }
        int payment = info.getTotalPayment() != null ? info.getTotalPayment()
                    : info.getPayment() != null ? info.getPayment() : 0;
        int transferCount;
        if (info.getTransitCount() != null) {
            transferCount = info.getTransitCount();
        } else {
            int legs = (info.getBusTransitCount() != null ? info.getBusTransitCount() : 0)
                     + (info.getSubwayTransitCount() != null ? info.getSubwayTransitCount() : 0);
            transferCount = legs > 0 ? legs - 1 : 0;
        }
        return new PathInfo(
                info.getTotalTime() != null ? info.getTotalTime() : 0,
                payment,
                info.getTotalWalk() != null ? info.getTotalWalk() : 0,
                transferCount,
                info.getFirstStartStation() != null ? info.getFirstStartStation() : "",
                info.getLastEndStation() != null ? info.getLastEndStation() : "",
                info.getMapObj()
        );
    }

    private SubPathResult toSubPathResult(OdSaySubPath subPath) {
        List<LaneResult> lanes = subPath.getLane() != null
                ? subPath.getLane().stream().map(this::toLaneResult).toList()
                : Collections.emptyList();

        List<StationResult> stations = Collections.emptyList();
        if (subPath.getPassStopList() != null && subPath.getPassStopList().getStations() != null) {
            stations = subPath.getPassStopList().getStations().stream()
                    .map(this::toStationResult)
                    .toList();
        }

        return new SubPathResult(
                subPath.getTrafficType() != null ? subPath.getTrafficType() : 0,
                subPath.getSectionTime() != null ? subPath.getSectionTime() : 0,
                subPath.getDistance(),
                lanes,
                stations,
                subPath.getStartName(),
                subPath.getEndName(),
                subPath.getStartX(),
                subPath.getStartY(),
                subPath.getEndX(),
                subPath.getEndY(),
                subPath.getTrainType(),
                subPath.getPayment(),
                subPath.getStartID(),
                subPath.getWay(),
                subPath.getWayCode(),
                null,                       // polyline
                subPath.getStartExitNo(),
                subPath.getStartExitX(),
                subPath.getStartExitY(),
                subPath.getEndExitNo(),
                subPath.getEndExitX(),
                subPath.getEndExitY(),
                null                        // walkSteps
        );
    }

    private LaneResult toLaneResult(OdSayLane lane) {
        return new LaneResult(
                lane.getName(),
                lane.getBusNo(),
                lane.getSubwayCode(),
                lane.getType()
        );
    }

    private StationResult toStationResult(OdSayStation station) {
        Double x = null;
        Double y = null;
        try { if (station.getX() != null) x = Double.parseDouble(station.getX()); } catch (NumberFormatException ignored) {}
        try { if (station.getY() != null) y = Double.parseDouble(station.getY()); } catch (NumberFormatException ignored) {}
        return new StationResult(
                station.getIndex() != null ? station.getIndex() : 0,
                station.getStationID(),
                station.getStationName(),
                x,
                y
        );
    }
}
