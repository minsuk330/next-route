package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.route.config.OtpRoutingProperties;
import watoo.grd.nextroute.application.subway.dto.OtpRailRoute;
import watoo.grd.nextroute.application.subway.dto.RailPattern;
import watoo.grd.nextroute.application.subway.port.out.SubwayRoutePatternPort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OTP 지하철 노선·패턴 조회(#283). 파싱은 {@link #parseRoutes}·{@link #parsePatterns}(순수)가 한다.
 *
 * <p>{@link OtpRoutingAdapter} 와 같은 이유로 {@link SimpleClientHttpRequestFactory} 로 POST 한다 —
 * {@code JdkClientHttpRequestFactory} 는 이 앱에서 POST body 를 보내다 멈춘다(2026-09-17 실측).
 *
 * <p>패턴 응답은 trip 마다 stoptimes 를 다 싣는다(2호선 본선 약 1.4MB, 1~2.5초, 2026-10-07 실측).
 * GraphQL 로 trip 수를 줄일 방법이 없어 그대로 받고, 부르는 쪽이 하루 단위로 캐시한다.
 *
 * <p>응답 대기 한계는 호출마다 부르는 쪽이 정한다(남은 예산). 고정 15초를 노선마다 쓰면 OTP 가 느린
 * 날 첫 호출이 45초까지 늘었다(#291).
 */
@Slf4j
@Component
public class OtpSubwayRoutePatternAdapter implements SubwayRoutePatternPort {

    private static final String GRAPHQL_PATH = "/otp/routers/default/index/graphql";

    private static final String ROUTES_QUERY =
            "{ routes(transportModes:[SUBWAY]) { gtfsId shortName longName } }";
    private static final String PATTERNS_QUERY =
            "query($id:String!){ route(id:$id){ patterns{ stops{ name lat lon } "
                    + "trips{ stoptimes{ scheduledArrival scheduledDeparture pickupType dropoffType } } } } }";

    private static final String NONE = "NONE";

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final OtpRoutingProperties properties;

    public OtpSubwayRoutePatternAdapter(RestClient.Builder restClientBuilder, ObjectMapper objectMapper,
                                        OtpRoutingProperties properties) {
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public List<OtpRailRoute> subwayRoutes(Duration timeout) {
        return parseRoutes(post(Map.of("query", ROUTES_QUERY), timeout));
    }

    @Override
    public List<RailPattern> patterns(String routeGtfsId, Duration timeout) {
        JsonNode root = post(Map.of("query", PATTERNS_QUERY, "variables", Map.of("id", routeGtfsId)), timeout);
        return parsePatterns(routeGtfsId, root.path("data").path("route"));
    }

    static List<OtpRailRoute> parseRoutes(JsonNode root) {
        List<OtpRailRoute> routes = new ArrayList<>();
        for (JsonNode route : root.path("data").path("routes")) {
            routes.add(new OtpRailRoute(
                    route.path("gtfsId").asText(null),
                    route.path("shortName").asText(null),
                    route.path("longName").asText(null)));
        }
        return routes;
    }

    /**
     * {@code route{patterns{...}}} 노드를 패턴 목록으로 읽는다. 시각은 첫 trip 의 것이다.
     *
     * <p>타지도 내리지도 않는 정차(pickup·dropoff 둘 다 {@code NONE})는 뺀다 — 통과하는 역을 남은
     * 역으로 보여 주면 거기서 내릴 수 있는 줄 안다. 출발역의 하차 금지·종착역의 승차 금지처럼
     * 한쪽만 막힌 것은 남긴다(실측 각 442건, 전부 패턴 양 끝).
     */
    public static List<RailPattern> parsePatterns(String routeGtfsId, JsonNode route) {
        List<RailPattern> patterns = new ArrayList<>();
        for (JsonNode pattern : route.path("patterns")) {
            JsonNode stops = pattern.path("stops");
            JsonNode trips = pattern.path("trips");
            JsonNode times = trips.size() > 0 ? trips.get(0).path("stoptimes") : null;
            boolean timed = times != null && times.size() == stops.size();
            List<RailPattern.Stop> parsed = new ArrayList<>(stops.size());
            for (int i = 0; i < stops.size(); i++) {
                JsonNode stop = stops.get(i);
                JsonNode time = timed ? times.get(i) : null;
                if (time != null && NONE.equals(time.path("pickupType").asText())
                        && NONE.equals(time.path("dropoffType").asText())) {
                    continue;
                }
                parsed.add(new RailPattern.Stop(
                        stop.path("name").asText(null),
                        stop.path("lat").asDouble(),
                        stop.path("lon").asDouble(),
                        intOrNull(time, "scheduledArrival"),
                        intOrNull(time, "scheduledDeparture")));
            }
            patterns.add(new RailPattern(routeGtfsId, trips.size(), List.copyOf(parsed)));
        }
        return patterns;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        return node.get(field).asInt();
    }

    private JsonNode post(Map<String, Object> body, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration connect = Duration.ofMillis(properties.getConnectTimeoutMs());
        factory.setConnectTimeout(connect.compareTo(timeout) < 0 ? connect : timeout);
        factory.setReadTimeout(timeout);
        RestClient client = restClientBuilder.clone().requestFactory(factory).build();
        String response = client.post()
                .uri(properties.getBaseUrl() + GRAPHQL_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("OTP 빈 응답");
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            if (root.has("errors")) {
                throw new IllegalStateException("OTP GraphQL 오류: " + root.path("errors").path(0).path("message").asText());
            }
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
