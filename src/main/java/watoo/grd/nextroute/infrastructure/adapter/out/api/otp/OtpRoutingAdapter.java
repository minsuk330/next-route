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
import watoo.grd.nextroute.application.route.routing.RailServiceClassifier;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingFailure;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.RoutingOutcome;
import watoo.grd.nextroute.application.route.routing.RoutingPort;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingQuery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * OTP 2.9.0 planConnection 어댑터. 선택된 provider 가 OTP 일 때만 호출된다(selector 는 PR2b).
 *
 * <p>파싱/매핑은 {@link OtpPlanConnectionMapper}(순수)가 하고, 여기서는 HTTP 와 오류 분류만 한다.
 * read timeout 은 {@link RoutingDeadline} 의 남은 예산으로 요청마다 건다(OdSay 어댑터와 같은 패턴).
 * 실패는 예외가 아니라 {@link RoutingFailure} 로 반환한다.
 *
 * <p><b>{@link SimpleClientHttpRequestFactory}(HttpURLConnection 기반) 사용, {@code JdkClientHttpRequestFactory}
 * 아님</b> — 2026-09-17 전환 실측: OTP 서버가 POST body 를 다 못 받고 EOFException 을 던지며 정확히
 * 예산(otpMs) 만큼 걸려 취소됨(otp CPU 0%, 순수 대기). JdkClientHttpRequestFactory 는 body 를
 * {@code HttpRequest.BodyPublishers.fromPublisher}(Flow.Publisher, 비동기) 로 보내는데, 이 경로에서
 * body flush 가 멈추는 현상 재현(격리 테스트 — OTP 직접/도커내부망 직접 호출은 전부 1~1.5s 로 정상,
 * 이 앱의 JdkClientHttpRequestFactory 경유만 매번 3.5s 벽에서 취소). ODSAY 어댑터가 같은
 * JdkClientHttpRequestFactory 를 오래 문제없이 써온 건 GET(body 없음)이라 이 경로를 안 탔기 때문 —
 * "OTP 가 느리다"가 아니라 "이 앱에서 POST body 를 보내는 유일한 hot-path 어댑터"라 여기서만 드러남.
 */
@Slf4j
@Component
public class OtpRoutingAdapter implements RoutingPort {

    private static final String GRAPHQL_PATH = "/otp/routers/default/index/graphql";
    private static final ZoneId FEED_ZONE = ZoneId.of("Asia/Seoul");
    /**
     * OTP GTFS GraphQL {@code TransitMode} 전 값. {@code modes.transit.transit} 에 목록을 주면 <b>그 수단만</b>
     * 탐색한다(목록이 없을 때만 전 수단) — 버스 가중치 하나를 주려 해도 나머지를 전부 나열해야 탐색 범위가 그대로다.
     */
    private static final List<String> ALL_TRANSIT_MODES = List.of(
            "AIRPLANE", "BUS", "CABLE_CAR", "CARPOOL", "COACH", "FERRY", "FUNICULAR", "GONDOLA",
            "MONORAIL", "RAIL", "SNOW_AND_ICE", "SUBWAY", "TAXI", "TRAM", "TROLLEYBUS");
    /**
     * 중립 수단 → OTP 수단. {@link RoutingQuery#modes()} 가 비어 있지 않으면 이것만 탐색한다
     * (검색이 고유 경로를 채우려고 "지하철만"·"버스만"을 따로 묻는다 — OTP 는 지배당한 대안을 버리기 때문).
     */
    private static final Map<RoutingMode, List<String>> OTP_MODES = Map.of(
            RoutingMode.BUS, List.of("BUS", "COACH", "TROLLEYBUS"),
            RoutingMode.SUBWAY, List.of("SUBWAY", "MONORAIL", "TRAM"),
            RoutingMode.RAIL, List.of("RAIL", "FUNICULAR"));
    /** 고정 탐색 창 안에 출발편이 없을 때 OTP 가 주는 routingErrors 코드. */
    private static final String NO_CONNECTION_IN_WINDOW = "NO_TRANSIT_CONNECTION_IN_SEARCH_WINDOW";
    /** 역 id 로 물었는데 이 실패면 좌표로 다시 묻는다(그 id 를 모르는 그래프 등). */
    private static final Set<String> STATION_FALLBACK_CODES = Set.of("GRAPHQL_ERROR", "LOCATION_NOT_FOUND");

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final OtpPlanConnectionMapper mapper;
    private final OtpRoutingProperties properties;
    private final OtpStationResolver stationResolver;
    private final Clock clock;
    private final RailServiceClassifier classifier;
    private final String query;

    public OtpRoutingAdapter(RestClient.Builder restClientBuilder, ObjectMapper objectMapper,
                             OtpPlanConnectionMapper mapper, OtpRoutingProperties properties,
                             OtpStationResolver stationResolver, Clock clock,
                             RailServiceClassifier classifier) {
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
        this.mapper = mapper;
        this.properties = properties;
        this.stationResolver = stationResolver;
        this.clock = clock;
        this.classifier = classifier;
        this.query = loadQuery();
    }

    @Override
    public RoutingProvider provider() {
        return RoutingProvider.OTP;
    }

    @Override
    public RoutingOutcome search(RoutingQuery routingQuery, RoutingDeadline deadline) {
        Endpoints snapped = endpoints(routingQuery, true);
        RoutingOutcome outcome = searchWithWindowFallback(routingQuery, deadline, snapped);
        if (snapped.usesStation() && outcome instanceof RoutingFailure failure
                && STATION_FALLBACK_CODES.contains(failure.code())) {
            log.info("[OTP] 역 id 검색 실패({}) → 좌표로 재검색", failure.code());
            return searchWithWindowFallback(routingQuery, deadline, endpoints(routingQuery, false));
        }
        return outcome;
    }

    private RoutingOutcome searchWithWindowFallback(RoutingQuery routingQuery, RoutingDeadline deadline,
                                                    Endpoints endpoints) {
        Duration window = properties.getSearchWindow();
        RoutingOutcome outcome = searchOnce(routingQuery, deadline, window, endpoints);
        // 고정 창 안에 출발편이 없으면 창을 빼고(OTP 자동 창) 한 번 더 묻는다. 배차가 창보다 긴
        // 지방 시외·심야는 고정 창에서 0건이 된다(2026-10-12 홍천→인제 14:00: 30분 창 0건, 자동 창 2건).
        // 같은 예산을 이어 쓴다. 예산이 바닥나면 TIMEOUT(NO_BUDGET) — 창 밖에 경로가 있는지 모르는 채
        // "경로 없음"이라 하지 않는다.
        if (window != null && outcome instanceof RoutingFailure failure
                && NO_CONNECTION_IN_WINDOW.equals(failure.code())) {
            log.info("[OTP] 탐색 창 {} 안에 연결 없음 → 자동 창으로 재검색", window);
            return searchOnce(routingQuery, deadline, null, endpoints);
        }
        return outcome;
    }

    /**
     * 출발·도착을 OTP 위치 입력으로. 장소가 역이면 그 승강장의 가상 역 id({@code stopLocation}) — 승강장과
     * 모든 출구에서 출발해 가까운 출구에서 걷는다 — 아니면 좌표({@link OtpStationResolver}).
     */
    private Endpoints endpoints(RoutingQuery q, boolean snapToStations) {
        Optional<OtpStationResolver.Snap> origin = snapToStations
                ? stationResolver.snap(q.originLat(), q.originLon(), q.originName()) : Optional.empty();
        Optional<OtpStationResolver.Snap> dest = snapToStations
                ? stationResolver.snap(q.destLat(), q.destLon(), q.destName()) : Optional.empty();
        // 같은 역 안의 출발·도착(다른 노선 승강장 등)은 좌표로 — 출구가 겹쳐 검색이 뜻을 잃는다.
        if (origin.isPresent() && dest.isPresent()
                && origin.get().stationName().equals(dest.get().stationName())) {
            origin = Optional.empty();
            dest = Optional.empty();
        }
        return new Endpoints(
                location(origin.map(OtpStationResolver.Snap::stationId), q.originLat(), q.originLon()),
                location(dest.map(OtpStationResolver.Snap::stationId), q.destLat(), q.destLon()),
                origin.isPresent() || dest.isPresent());
    }

    private static Map<String, Object> location(Optional<String> station, double lat, double lon) {
        return station
                .<Map<String, Object>>map(id -> Map.of("location", Map.of("stopLocation", Map.of("stopLocationId", id))))
                .orElseGet(() -> Map.of("location", Map.of("coordinate", Map.of("latitude", lat, "longitude", lon))));
    }

    private record Endpoints(Map<String, Object> origin, Map<String, Object> destination, boolean usesStation) {
    }

    private RoutingOutcome searchOnce(RoutingQuery routingQuery, RoutingDeadline deadline, Duration window,
                                      Endpoints endpoints) {
        long budget = deadline.remainingMillis(clock);
        if (budget <= 0) {
            return RoutingFailure.of(RoutingFailure.Kind.TIMEOUT, "NO_BUDGET",
                    "OTP 호출 전에 시간 예산이 소진됨");
        }
        String body = requestBody(routingQuery, window, endpoints);
        String response;
        try {
            response = sendOnce(body, budget);
        } catch (OtpCallException e) {
            return RoutingFailure.of(e.kind, e.code, e.getMessage());
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (IOException e) {
            return RoutingFailure.of(RoutingFailure.Kind.ENGINE_ERROR, "BAD_JSON",
                    "OTP 응답 JSON 파싱 실패");
        }
        // HTTP 200 이어도 GraphQL 최상위 errors 가 있으면 엔진 오류로 분류(부분 응답 방지).
        JsonNode errors = root.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            String msg = errors.get(0).path("message").asText("GraphQL error");
            return RoutingFailure.of(RoutingFailure.Kind.ENGINE_ERROR, "GRAPHQL_ERROR", msg);
        }

        OtpPlanConnectionMapper.OtpVersions versions = new OtpPlanConnectionMapper.OtpVersions(
                properties.getSchemaVersion(), properties.getNetworkDataVersion(),
                properties.getGraphVersion());
        // 실시간 coverage 배선은 #1 검색 응답이 소비하지 않는다(정적 드롭인). 주행중 재탐색(#2 P2-F)이
        // 소비지점에서 적용한다 → 여기서는 중립 결과를 그대로 반환. OtpLegCoverageResolver 는 #2 용으로 존치.
        // 열차 종류는 여기 한 곳에서 붙인다(subway-express S2) — 본·추가·재탐색 검색이 모두 이 길을 탄다.
        return classifier.annotate(mapper.map(root, routingQuery.departureAt(), versions));
    }

    private String requestBody(RoutingQuery q, Duration window, Endpoints endpoints) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("origin", endpoints.origin());
        vars.put("destination", endpoints.destination());
        // offset 포함 확정 departureAt 을 feed 시간대로 표현(OTP OffsetDateTime).
        vars.put("when", q.departureAt().atZone(FEED_ZONE).toOffsetDateTime().toString());
        vars.put("first", properties.getFirst());
        if (window != null) {
            vars.put("searchWindow", window.toString());
        }
        // 수단 제한이 있으면 가중치와 무관하게 보낸다. 없으면 가중치가 있을 때만(1.0 이면 옛 요청과 같다).
        if (!q.modes().isEmpty() || properties.getBusReluctance() != 1.0) {
            vars.put("modes", Map.of("transit", Map.of("transit",
                    transitModes(otpModes(q.modes()), properties.getBusReluctance()))));
        }
        // 제외 노선 = 요청 값(급행 끄기·일반 대안 되찾기) + 공항철도 직통(언제나, D3·#495). 직통은 본·추가·되찾기·재탐색 모든
        // 검색이 이 길을 타므로 여기 한 곳에서 뺀다 — 결과에서만 거르면 직통이 OTP 후보 자리를 차지해 본선 대안이 줄어든다.
        // 그래프에 없는 id 를 보내도 OTP 는 오류 없이 무시한다(운영 2026-10-22 확인).
        Set<String> excluded = new TreeSet<>(q.excludedRouteIds());
        excluded.addAll(classifier.directRouteIds());
        Map<String, Object> transit = new LinkedHashMap<>();
        if (!excluded.isEmpty()) {
            transit.put("filters", List.of(Map.of("exclude", List.of(Map.of("routes", List.copyOf(excluded))))));
        }
        // 환승 여유: 걷는 시간(통로·피드의 최소 환승 시간) 위에 더 얹는 여유. OTP 기본은 2분이다.
        Integer slack = properties.getTransferSlackSeconds();
        if (slack != null && slack >= 0) {
            transit.put("transfer", Map.of("slack", Duration.ofSeconds(slack).toString()));
        }
        if (!transit.isEmpty()) {
            vars.put("preferences", Map.of("transit", transit));
        }
        // 버스 합치기(otp-bus-lane-merge): 검색 화면 질의만 같은 정류장의 다른 노선을 함께 묻는다.
        if (q.alternativeLegs() && properties.isBusLaneMergeEnabled() && properties.getAlternativeLegs() > 0) {
            vars.put("withAlternatives", true);
            vars.put("alternativeLegs", properties.getAlternativeLegs());
        }
        try {
            return objectMapper.writeValueAsString(Map.of("query", query, "variables", vars));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> otpModes(Set<RoutingMode> modes) {
        if (modes.isEmpty()) {
            return ALL_TRANSIT_MODES;
        }
        return ALL_TRANSIT_MODES.stream()
                .filter(m -> modes.stream().anyMatch(rm -> OTP_MODES.getOrDefault(rm, List.of()).contains(m)))
                .toList();
    }

    private static List<Map<String, Object>> transitModes(List<String> otpModes, double busReluctance) {
        List<Map<String, Object>> modes = new ArrayList<>(otpModes.size());
        for (String mode : otpModes) {
            modes.add("BUS".equals(mode)
                    ? Map.of("mode", mode, "cost", Map.of("reluctance", busReluctance))
                    : Map.of("mode", mode));
        }
        return modes;
    }

    private String sendOnce(String body, long budgetMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(budgetMs));
        RestClient client = restClientBuilder.clone().requestFactory(factory).build();
        ResponseEntity<String> response;
        try {
            response = client.post()
                    .uri(properties.getBaseUrl() + GRAPHQL_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> true, (req, res) -> { })  // 기본 예외 억제 — 아래서 분류
                    .toEntity(String.class);
        } catch (Exception e) {
            // 연결/read timeout 등 I/O. 예산 초과는 TIMEOUT(504), 그 밖은 ENGINE_ERROR(502).
            // 메시지 문자열로 가르면 안 된다 — 본문을 읽다 끊기면 Spring 이 SocketTimeoutException 을
            // "Error while extracting response ..." 로 감싸 "timeout" 이 사라진다(2026-10-05 장거리 검색 502).
            // 예외 원문은 로그에만 남기고 응답 메시지에는 싣지 않는다.
            if (isTimeout(e)) {
                log.warn("[OTP] 응답 시간 초과 budgetMs={}", budgetMs);
                throw new OtpCallException(RoutingFailure.Kind.TIMEOUT, "TIMEOUT",
                        "OTP 응답 시간 초과");
            }
            log.warn("[OTP] 호출 실패", e);
            throw new OtpCallException(RoutingFailure.Kind.ENGINE_ERROR, "IO",
                    "OTP 호출 실패(" + rootCause(e).getClass().getSimpleName() + ")");
        }
        int status = response.getStatusCode().value();
        if (status >= 500) {
            throw new OtpCallException(RoutingFailure.Kind.ENGINE_ERROR, "HTTP_" + status, "OTP 5xx");
        }
        if (status >= 400) {
            throw new OtpCallException(RoutingFailure.Kind.ENGINE_ERROR, "HTTP_" + status, "OTP 4xx");
        }
        String responseBody = response.getBody();
        if (responseBody == null || responseBody.isBlank()) {
            throw new OtpCallException(RoutingFailure.Kind.ENGINE_ERROR, "EMPTY", "OTP 빈 응답");
        }
        return responseBody;
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    private String loadQuery() {
        try {
            return new ClassPathResource("otp/planConnection.graphql")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("planConnection.graphql 로드 실패", e);
        }
    }

    /** 내부 전용 — HTTP 단계 실패를 RoutingFailure 로 옮기기 위한 캐리어. */
    private static final class OtpCallException extends RuntimeException {
        private final RoutingFailure.Kind kind;
        private final String code;

        OtpCallException(RoutingFailure.Kind kind, String code, String message) {
            super(message);
            this.kind = kind;
            this.code = code;
        }
    }
}
