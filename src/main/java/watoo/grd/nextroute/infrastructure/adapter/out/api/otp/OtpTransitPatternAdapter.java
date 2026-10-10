package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.route.config.OtpRoutingProperties;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OTP 노선 패턴 조회(#265 단독 추적). 쿼리는 {@code otp/trackPatterns.graphql} 한 파일에 operation 별로 있다.
 *
 * <p>{@link OtpRoutingAdapter} 와 같은 이유로 {@link SimpleClientHttpRequestFactory} 로 POST 한다 —
 * {@code JdkClientHttpRequestFactory} 는 이 앱에서 POST body 를 보내다 멈춘다(2026-09-17 실측).
 * read timeout 은 호출마다 {@link RoutingDeadline} 의 남은 시간이다. permit 은 부르는 쪽이 잡는다.
 *
 * <p>파싱은 {@code parse*}(순수, 패키지 공개)가 한다 — 실측 응답 축약본으로 시험한다.
 */
@Slf4j
@Component
public class OtpTransitPatternAdapter implements TransitPatternPort {

    private static final String GRAPHQL_PATH = "/otp/routers/default/index/graphql";
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final OtpRoutingProperties properties;
    private final Clock clock;
    private final String query;

    public OtpTransitPatternAdapter(RestClient.Builder restClientBuilder, ObjectMapper objectMapper,
                                    OtpRoutingProperties properties, Clock clock) {
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.query = loadQuery();
    }

    @Override
    public List<NearStop> stopsNear(double lat, double lon, int radiusMeters, int limit,
                                       RoutingDeadline deadline) {
        double dLat = Math.toDegrees(radiusMeters / EARTH_RADIUS_METERS);
        double dLon = dLat / Math.cos(Math.toRadians(lat));
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("minLat", lat - dLat);
        vars.put("minLon", lon - dLon);
        vars.put("maxLat", lat + dLat);
        vars.put("maxLon", lon + dLon);
        return parseStopsNear(post("StopsNear", vars, deadline), lat, lon, radiusMeters, limit);
    }

    @Override
    public List<TransitStop> stops(List<String> gtfsStopIds, RoutingDeadline deadline) {
        if (gtfsStopIds.isEmpty()) {
            return List.of();
        }
        return parseStopsByIds(post("StopsByIds", Map.of("ids", gtfsStopIds), deadline));
    }

    @Override
    public List<TransitStop> stopsInBox(double minLat, double minLon, double maxLat, double maxLon,
                                        RoutingDeadline deadline) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("minLat", minLat);
        vars.put("minLon", minLon);
        vars.put("maxLat", maxLat);
        vars.put("maxLon", maxLon);
        return parseStopsInBox(post("StopsInBox", vars, deadline));
    }

    @Override
    public List<TransitPattern> patternsAtStop(String gtfsStopId, RoutingDeadline deadline) {
        return parsePatternsAtStop(post("PatternsAtStop", Map.of("id", gtfsStopId), deadline));
    }

    @Override
    public Optional<TransitPatternDetail> pattern(String patternCode, RoutingDeadline deadline) {
        return parsePatternDetail(post("PatternDetail", Map.of("code", patternCode), deadline));
    }

    @Override
    public List<TransitTripRef> tripsForDate(String patternCode, LocalDate serviceDate, RoutingDeadline deadline) {
        return parseTripsForDate(post("TripsForDate",
                Map.of("code", patternCode, "date", serviceDate.toString()), deadline));
    }

    @Override
    public List<TransitStopTime> tripStopTimes(String tripGtfsId, LocalDate serviceDate, RoutingDeadline deadline) {
        return parseTripStopTimes(post("TripStopTimes",
                Map.of("id", tripGtfsId, "date", serviceDate.toString()), deadline));
    }

    // ── 파싱(순수) ───────────────────────────────────────────────────────────────

    /**
     * 사각형 안 정류장을 직선거리로 반경 안만 남겨 가까운 순으로 {@code limit} 개. 거리는 OTP 가 아니라 여기서 잰다
     * (graphql 머리말: stopsByRadius 는 도보 탐색 거리라 좌표에 따라 0개가 된다).
     */
    public static List<NearStop> parseStopsNear(JsonNode root, double lat, double lon, int radiusMeters, int limit) {
        List<NearStop> stops = new ArrayList<>();
        for (JsonNode stop : root.path("data").path("stopsByBbox")) {
            String id = text(stop.path("gtfsId"));
            if (id == null) {
                continue;
            }
            TransitStop transitStop = toStop(stop);
            int distance = (int) Math.round(distanceMeters(lat, lon, transitStop.lat(), transitStop.lon()));
            if (distance > radiusMeters) {
                continue;
            }
            // 노선은 방향마다 따로라 같은 이름이 두 번 온다(서울2호선 ×2).
            java.util.LinkedHashSet<String> routeNames = new java.util.LinkedHashSet<>();
            for (JsonNode route : stop.path("routes")) {
                String name = text(route.path("shortName"));
                if (name != null) {
                    routeNames.add(name);
                }
            }
            stops.add(new NearStop(transitStop, distance, List.copyOf(routeNames)));
        }
        stops.sort(java.util.Comparator.comparingInt(NearStop::distanceMeters));
        return stops.size() > limit ? List.copyOf(stops.subList(0, limit)) : stops;
    }

    /** 반경 수백 m 라 등장방형 근사로 충분하다. */
    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = Math.toRadians(lat2 - lat1);
        return Math.sqrt(x * x + y * y) * EARTH_RADIUS_METERS;
    }

    /** 없는 id 는 null 원소로 온다 — 건너뛴다. */
    public static List<TransitStop> parseStopsByIds(JsonNode root) {
        List<TransitStop> stops = new ArrayList<>();
        for (JsonNode stop : root.path("data").path("stops")) {
            if (stop.isNull() || text(stop.path("gtfsId")) == null) {
                continue;
            }
            stops.add(toStop(stop));
        }
        return stops;
    }

    public static List<TransitStop> parseStopsInBox(JsonNode root) {
        List<TransitStop> stops = new ArrayList<>();
        for (JsonNode stop : root.path("data").path("stopsByBbox")) {
            if (stop.isNull() || text(stop.path("gtfsId")) == null) {
                continue;
            }
            stops.add(toStop(stop));
        }
        return stops;
    }

    public static List<TransitPattern> parsePatternsAtStop(JsonNode root) {
        JsonNode stop = root.path("data").path("stop");
        List<TransitPattern> patterns = new ArrayList<>();
        for (JsonNode pattern : stop.path("patterns")) {
            List<PatternStop> stops = new ArrayList<>();
            for (JsonNode s : pattern.path("stops")) {
                stops.add(new PatternStop(text(s.path("gtfsId")), text(s.path("name"))));
            }
            patterns.add(new TransitPattern(text(pattern.path("code")), text(pattern.path("headsign")),
                    toRoute(pattern.path("route")), stops, pattern.path("trips").size()));
        }
        return patterns;
    }

    public static Optional<TransitPatternDetail> parsePatternDetail(JsonNode root) {
        JsonNode pattern = root.path("data").path("pattern");
        if (pattern.isMissingNode() || pattern.isNull()) {
            return Optional.empty();
        }
        List<TransitStop> stops = new ArrayList<>();
        for (JsonNode s : pattern.path("stops")) {
            stops.add(toStop(s));
        }
        return Optional.of(new TransitPatternDetail(text(pattern.path("code")), toRoute(pattern.path("route")),
                stops, text(pattern.path("patternGeometry").path("points"))));
    }

    public static List<TransitTripRef> parseTripsForDate(JsonNode root) {
        List<TransitTripRef> trips = new ArrayList<>();
        for (JsonNode trip : root.path("data").path("pattern").path("tripsForDate")) {
            JsonNode dep = trip.path("departureStoptime").path("scheduledDeparture");
            trips.add(new TransitTripRef(text(trip.path("gtfsId")), dep.isNumber() ? dep.asInt() : null));
        }
        return trips;
    }

    public static List<TransitStopTime> parseTripStopTimes(JsonNode root) {
        List<TransitStopTime> times = new ArrayList<>();
        for (JsonNode st : root.path("data").path("trip").path("stoptimesForDate")) {
            times.add(new TransitStopTime(text(st.path("stop").path("gtfsId")),
                    st.path("scheduledArrival").asInt(), st.path("scheduledDeparture").asInt(),
                    !"NONE".equals(st.path("pickupType").asText()),
                    !"NONE".equals(st.path("dropoffType").asText())));
        }
        return times;
    }

    private static TransitStop toStop(JsonNode stop) {
        return new TransitStop(text(stop.path("gtfsId")), text(stop.path("name")),
                stop.path("lat").asDouble(), stop.path("lon").asDouble(),
                toMode(stop.path("vehicleMode").asText("")));
    }

    private static TransitRoute toRoute(JsonNode route) {
        String otpMode = route.path("mode").asText("");
        String color = text(route.path("color"));
        return new TransitRoute(text(route.path("gtfsId")), text(route.path("shortName")),
                text(route.path("longName")), toMode(otpMode), otpMode.isEmpty() ? null : otpMode,
                color == null || color.isBlank() ? null : color,
                text(route.path("agency").path("gtfsId")));
    }

    /** {@code OtpPlanConnectionMapper.toMode} 와 같은 규칙(TRAM 은 지하철로 본다). */
    static RoutingMode toMode(String otpMode) {
        return switch (otpMode) {
            case "WALK" -> RoutingMode.WALK;
            case "BUS" -> RoutingMode.BUS;
            case "SUBWAY", "TRAM" -> RoutingMode.SUBWAY;
            case "RAIL" -> RoutingMode.RAIL;
            default -> RoutingMode.UNSUPPORTED;
        };
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? null : n.asText();
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    private JsonNode post(String operationName, Map<String, Object> variables, RoutingDeadline deadline) {
        long budget = deadline.remainingMillis(clock);
        if (budget <= 0) {
            throw new TransitPatternUnavailableException("OTP 호출 전에 시간 예산이 소진됨");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(budget));
        RestClient client = restClientBuilder.clone().requestFactory(factory).build();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("operationName", operationName);
        body.put("variables", variables);
        ResponseEntity<String> response;
        try {
            response = client.post()
                    .uri(properties.getBaseUrl() + GRAPHQL_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> true, (req, res) -> { })
                    .toEntity(String.class);
        } catch (Exception e) {
            log.warn("[OTP-track] {} 호출 실패: {}", operationName, e.getClass().getSimpleName());
            throw new TransitPatternUnavailableException("OTP 호출 실패(" + operationName + ")", e);
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new TransitPatternUnavailableException(
                    "OTP HTTP " + response.getStatusCode().value() + "(" + operationName + ")");
        }
        String responseBody = response.getBody();
        if (responseBody == null || responseBody.isBlank()) {
            throw new TransitPatternUnavailableException("OTP 빈 응답(" + operationName + ")");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (IOException e) {
            throw new TransitPatternUnavailableException("OTP 응답 JSON 파싱 실패(" + operationName + ")", e);
        }
        JsonNode errors = root.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            throw new TransitPatternUnavailableException("OTP GraphQL 오류(" + operationName + "): "
                    + errors.get(0).path("message").asText(""));
        }
        return root;
    }

    private String loadQuery() {
        try {
            return new ClassPathResource("otp/trackPatterns.graphql").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("trackPatterns.graphql 로드 실패", e);
        }
    }
}
