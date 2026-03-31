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
                .queryParam("SearchType", 0)
                .build(true)
                .toUri();

        OdSaySearchResponse response = callApi(uri);

        if (response.getError() != null && !response.getError().isNull()) {
            JsonNode errorNode = response.getError();
            // error가 배열인 경우 첫 번째 요소, 객체인 경우 그대로 사용
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
                subPaths
        );
    }

    private PathInfo toPathInfo(OdSayPathInfo info) {
        if (info == null) {
            return new PathInfo(0, 0, 0, 0, "", "");
        }
        int transferCount = (info.getBusTransitCount() != null ? info.getBusTransitCount() : 0)
                + (info.getSubwayTransitCount() != null ? info.getSubwayTransitCount() : 0);
        if (transferCount > 0) {
            transferCount--;
        }
        return new PathInfo(
                info.getTotalTime() != null ? info.getTotalTime() : 0,
                info.getPayment() != null ? info.getPayment() : 0,
                info.getTotalWalk() != null ? info.getTotalWalk() : 0,
                transferCount,
                info.getFirstStartStation() != null ? info.getFirstStartStation() : "",
                info.getLastEndStation() != null ? info.getLastEndStation() : ""
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
                subPath.getEndName()
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
