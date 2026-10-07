package watoo.grd.nextroute.infrastructure.adapter.out.api.regionalbus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop;
import watoo.grd.nextroute.application.regionalbus.dto.TagoCity;
import watoo.grd.nextroute.application.regionalbus.dto.TagoRoute;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteSourcePort;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusSourceException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 경기·TAGO 노선 원천(#267). 월 1회 적재 배치만 쓴다 — 캐시·동시성 제한은 배치가 맡는다.
 *
 * <p>응답 모양(2026-10-06 실측):
 * <ul>
 *   <li>TAGO: {@code response.header.resultCode} "00" 정상, "99" 동시 세션 한도(일시). 결과 없으면
 *       {@code "items": ""}, 한 건이면 {@code item} 이 배열이 아니라 객체.</li>
 *   <li>경기: {@code response.msgHeader.resultCode} 0 정상, 4 결과 없음. 목록이 한 건이면 객체.</li>
 *   <li>둘 다 포털 공통 오류는 {@code OpenAPI_ServiceResponse.cmmMsgHeader.returnReasonCode}. 20/22/30~33
 *       (미등록 키·한도 초과 등)은 그날 계속 실패한다.</li>
 * </ul>
 *
 * <p>도착 어댑터({@code TagoBusArrivalAdapter})와 달리 {@code ApiCallBlocker} 를 건드리지 않는다. 노선정보는
 * 다른 서비스라 여기서의 미등록·한도 초과가 도착 조회를 막으면 안 된다.
 */
@Slf4j
@Component
public class RegionalBusRouteSourceAdapter implements RegionalBusRouteSourcePort {

    static final int PAGE_SIZE = 1000;
    private static final Set<String> BLOCKING_REASON_CODES = Set.of("20", "22", "30", "31", "32", "33");
    private static final String WAYPOINT_MARK = "(경유)";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String tagoBaseUrl;
    private final String gyeonggiBaseUrl;
    private final String tagoKey;
    private final String gyeonggiKey;

    public RegionalBusRouteSourceAdapter(RestClient restClient,
                                         ObjectMapper objectMapper,
                                         @Value("${tago.api.bus-route-base-url}") String tagoBaseUrl,
                                         @Value("${gyeonggi.api.bus-route-base-url}") String gyeonggiBaseUrl,
                                         @Value("${tago.api.bus-key:}") String tagoKey,
                                         @Value("${gyeonggi.api.bus-key:}") String gyeonggiKey) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.tagoBaseUrl = tagoBaseUrl;
        this.gyeonggiBaseUrl = gyeonggiBaseUrl;
        this.tagoKey = tagoKey;
        this.gyeonggiKey = gyeonggiKey;
    }

    @Override
    public List<TagoCity> tagoCities() {
        JsonNode body = tago("/getCtyCodeList?_type=json");
        return items(body, i -> new TagoCity(text(i.path("citycode")), text(i.path("cityname"))));
    }

    @Override
    public List<TagoRoute> tagoRoutes(String cityCode) {
        List<TagoRoute> routes = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode body = tago("/getRouteNoList?_type=json&numOfRows=" + PAGE_SIZE + "&pageNo=" + page
                    + "&cityCode=" + enc(cityCode));
            routes.addAll(items(body, i -> new TagoRoute(cityCode, text(i.path("routeid")), text(i.path("routeno")))));
            if ((long) page * PAGE_SIZE >= body.path("totalCount").asLong(0)) {
                return routes;
            }
        }
    }

    @Override
    public List<RegionalRouteStop> tagoRouteStops(String cityCode, String routeId) {
        List<RegionalRouteStop> stops = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode body = tago("/getRouteAcctoThrghSttnList?_type=json&numOfRows=" + PAGE_SIZE + "&pageNo=" + page
                    + "&cityCode=" + enc(cityCode) + "&routeId=" + enc(routeId));
            stops.addAll(items(body, RegionalBusRouteSourceAdapter::toTagoStop));
            if ((long) page * PAGE_SIZE >= body.path("totalCount").asLong(0)) {
                return stops;
            }
        }
    }

    @Override
    public List<RegionalRouteStop> gyeonggiRouteStops(String routeId) {
        requireKey(gyeonggiKey);
        JsonNode root = call(URI.create(gyeonggiBaseUrl + "/getBusRouteStationListv2?serviceKey=" + gyeonggiKey
                + "&format=json&routeId=" + enc(routeId)));
        JsonNode response = root.path("response");
        int resultCode = response.path("msgHeader").path("resultCode").asInt(-1);
        if (resultCode == 4) {
            return List.of();   // 결과 없음
        }
        if (resultCode != 0) {
            throw RegionalBusSourceException.failed("gyeonggi resultCode=" + resultCode + " "
                    + response.path("msgHeader").path("resultMessage").asText(""));
        }
        return list(response.path("msgBody").path("busRouteStationList"),
                RegionalBusRouteSourceAdapter::toGyeonggiStop);
    }

    /** TAGO 호출 + 정상 여부 판정. 돌려주는 값은 {@code response.body}. */
    private JsonNode tago(String pathAndQuery) {
        requireKey(tagoKey);
        JsonNode root = call(URI.create(tagoBaseUrl + pathAndQuery + "&serviceKey=" + tagoKey));
        JsonNode header = root.path("response").path("header");
        String resultCode = header.path("resultCode").asText("");
        if ("99".equals(resultCode)) {
            throw RegionalBusSourceException.retryable("tago resultCode=99 " + header.path("resultMsg").asText(""));
        }
        if (!"00".equals(resultCode)) {
            throw RegionalBusSourceException.failed("tago resultCode=" + resultCode + " "
                    + header.path("resultMsg").asText(""));
        }
        return root.path("response").path("body");
    }

    private JsonNode call(URI uri) {
        String body;
        try {
            body = restClient.get().uri(uri).exchange((req, res) ->
                    new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (ResourceAccessException e) {
            // 연결·읽기 시간 초과 같은 I/O 오류는 잠시 뒤 다시 하면 된다. 실패로 두면 한 번의 timeout 이
            // 적재 전체를 중단시킨다(2026-10-07 TAGO getRouteNoList Read timed out → aborted=true).
            throw RegionalBusSourceException.retryable("call failed: " + e.getMessage());
        } catch (Exception e) {
            throw RegionalBusSourceException.failed("call failed: " + e.getMessage());
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw RegionalBusSourceException.failed("non-JSON response");
        }
        if (root == null) {
            throw RegionalBusSourceException.failed("empty response");
        }
        JsonNode portalError = root.path("OpenAPI_ServiceResponse").path("cmmMsgHeader");
        if (!portalError.isMissingNode()) {
            String reason = portalError.path("returnReasonCode").asText("");
            String message = "portal error reasonCode=" + reason + " " + portalError.path("errMsg").asText("");
            throw BLOCKING_REASON_CODES.contains(reason)
                    ? RegionalBusSourceException.blocking(message)
                    : RegionalBusSourceException.failed(message);
        }
        return root;
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw RegionalBusSourceException.blocking("api key not configured");
        }
    }

    /** TAGO {@code body.items.item}: {@code items} 가 {@code ""} 이면 0건, {@code item} 이 객체면 1건. */
    static <T> List<T> items(JsonNode body, Function<JsonNode, T> mapper) {
        JsonNode items = body.path("items");
        if (!items.isObject()) {
            return List.of();
        }
        return list(items.path("item"), mapper);
    }

    static <T> List<T> list(JsonNode node, Function<JsonNode, T> mapper) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        List<T> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(i -> out.add(mapper.apply(i)));
        } else if (node.isObject()) {
            out.add(mapper.apply(node));
        }
        return out;
    }

    static RegionalRouteStop toTagoStop(JsonNode i) {
        return new RegionalRouteStop(
                text(i.path("nodeid")),
                text(i.path("nodenm")),
                i.path("nodeord").asInt(),
                null,
                intOrNull(i.path("updowncd")),
                null,
                doubleOrNull(i.path("gpslati")),
                doubleOrNull(i.path("gpslong")));
    }

    static RegionalRouteStop toGyeonggiStop(JsonNode i) {
        String name = text(i.path("stationName"));
        return new RegionalRouteStop(
                text(i.path("stationId")),
                name,
                i.path("stationSeq").asInt(),
                "Y".equalsIgnoreCase(i.path("turnYn").asText("")),
                null,
                name != null && name.contains(WAYPOINT_MARK),
                doubleOrNull(i.path("y")),
                doubleOrNull(i.path("x")));
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String s = node.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer intOrNull(JsonNode node) {
        String s = text(node);
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double doubleOrNull(JsonNode node) {
        String s = text(node);
        if (s == null) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
