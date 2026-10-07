package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Outcome;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Snapshot;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Vehicle;
import watoo.grd.nextroute.application.route.concurrent.SingleFlight;
import watoo.grd.nextroute.infrastructure.adapter.out.api.ApiCallBlocker;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 경기({@code buslocationservice/v2/getBusLocationListv2})·TAGO({@code BusLcInfoInqireService/getRouteAcctoBusLcList})
 * 버스 위치(#265). 같은 공공데이터포털 규칙이라 한 클래스에 둔다.
 *
 * <p>실측(2026-10-07):
 * <ul>
 *   <li>경기: {@code msgBody.busLocationList[]} 에 {@code vehId}·{@code plateNo}·{@code stationSeq}·{@code stateCd}
 *       (0 교차로 통과·1 정류소 도착·2 정류소 출발). {@code resultCode} 0 정상, 4 결과 없음(운행 차량 없음)</li>
 *   <li>TAGO: {@code body.items.item} 이 배열 또는 한 대면 객체, 없으면 {@code items: ""}. {@code vehicleno}·{@code nodeord}.
 *       상태 칸이 없다. {@code resultCode} "00" 정상</li>
 *   <li>포털 오류({@code OpenAPI_ServiceResponse}) 20·22·30~33 은 그날 계속 실패 → 자정까지 차단(도착 어댑터와 같은 규칙)</li>
 * </ul>
 *
 * <p>노선 단위 15초 캐시(성공만) + 같은 노선 동시 호출 병합. TAGO 는 포털 동시 세션 30 을 다른 기능과 나눠 쓰므로
 * 이 클라이언트 몫을 {@value #TAGO_MAX_CONCURRENT} 개로 묶는다.
 */
@Slf4j
@Component
public class RegionalBusLocationClient {

    static final String GG_API = "gyeonggi-bus-location";
    static final String TAGO_API = "tago-bus-location";
    static final Duration CACHE_TTL = Duration.ofSeconds(15);
    static final int TAGO_MAX_CONCURRENT = 4;
    private static final long JOIN_WAIT_MS = 5_000;
    private static final long PERMIT_WAIT_MS = 1_000;
    private static final Set<String> BLOCKING_REASON_CODES = Set.of("20", "22", "30", "31", "32", "33");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ApiCallBlocker blocker;
    private final Clock clock;
    private final String ggBaseUrl;
    private final String ggKey;
    private final String tagoBaseUrl;
    private final String tagoKey;

    final Semaphore tagoPermits = new Semaphore(TAGO_MAX_CONCURRENT);
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private final SingleFlight<String, Snapshot> flight = new SingleFlight<>();

    private record Cached(Snapshot snapshot, Instant expiresAt) {
    }

    public RegionalBusLocationClient(RestClient restClient, ObjectMapper objectMapper, ApiCallBlocker blocker,
                                     Clock clock,
                                     @Value("${gyeonggi.api.bus-location-base-url}") String ggBaseUrl,
                                     @Value("${gyeonggi.api.bus-key:}") String ggKey,
                                     @Value("${tago.api.bus-location-base-url}") String tagoBaseUrl,
                                     @Value("${tago.api.bus-key:}") String tagoKey) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.blocker = blocker;
        this.clock = clock;
        this.ggBaseUrl = ggBaseUrl;
        this.ggKey = ggKey;
        this.tagoBaseUrl = tagoBaseUrl;
        this.tagoKey = tagoKey;
    }

    public Snapshot gyeonggi(String routeId) {
        if (ggKey == null || ggKey.isBlank()) {
            return Snapshot.failed(Outcome.ERROR, clock.instant());
        }
        if (blocker.isBlocked(GG_API)) {
            return Snapshot.failed(Outcome.BLOCKED, clock.instant());
        }
        return cachedOrFetch("gg:" + routeId, () -> {
            URI uri = URI.create(ggBaseUrl + "/getBusLocationListv2?serviceKey=" + ggKey
                    + "&routeId=" + enc(routeId) + "&format=json");
            return call(uri, body -> parseGyeonggi(body, clock.instant()), GG_API);
        });
    }

    public Snapshot tago(String cityCode, String routeId) {
        if (tagoKey == null || tagoKey.isBlank()) {
            return Snapshot.failed(Outcome.ERROR, clock.instant());
        }
        if (blocker.isBlocked(TAGO_API)) {
            return Snapshot.failed(Outcome.BLOCKED, clock.instant());
        }
        return cachedOrFetch("tago:" + cityCode + ":" + routeId, () -> {
            boolean acquired;
            try {
                acquired = tagoPermits.tryAcquire(PERMIT_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Snapshot.failed(Outcome.ERROR, clock.instant());
            }
            if (!acquired) {
                return Snapshot.failed(Outcome.LIMITED, clock.instant());
            }
            try {
                URI uri = URI.create(tagoBaseUrl + "/getRouteAcctoBusLcList?serviceKey=" + tagoKey
                        + "&_type=json&numOfRows=100&cityCode=" + enc(cityCode) + "&routeId=" + enc(routeId));
                return call(uri, body -> parseTago(body, clock.instant()), TAGO_API);
            } finally {
                tagoPermits.release();
            }
        });
    }

    private Snapshot cachedOrFetch(String key, java.util.function.Supplier<Snapshot> fetch) {
        Cached hit = cache.get(key);
        if (hit != null && clock.instant().isBefore(hit.expiresAt())) {
            return hit.snapshot();
        }
        SingleFlight.Outcome<Snapshot> outcome = flight.execute(key, () -> {
            Snapshot snapshot = fetch.get();
            if (snapshot.isOk()) {
                cache.put(key, new Cached(snapshot, clock.instant().plus(CACHE_TTL)));
                Instant now = clock.instant();
                cache.entrySet().removeIf(e -> !now.isBefore(e.getValue().expiresAt()));
            }
            return snapshot;
        }, JOIN_WAIT_MS);
        return outcome.hasValue() ? outcome.value() : Snapshot.failed(Outcome.ERROR, clock.instant());
    }

    private Snapshot call(URI uri, java.util.function.Function<String, Snapshot> parser, String api) {
        String body;
        try {
            body = restClient.get().uri(uri).exchange((req, res) ->
                    new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("[BusLocation] {} call failed: {}", api, e.getMessage());
            return Snapshot.failed(Outcome.ERROR, clock.instant());
        }
        Snapshot snapshot = parser.apply(body);
        if (snapshot.outcome() == Outcome.BLOCKED) {
            blocker.block(api);
        }
        return snapshot;
    }

    // ── 파싱(순수) ───────────────────────────────────────────────────────────

    Snapshot parseGyeonggi(String body, Instant at) {
        JsonNode root = read(body);
        if (root == null) {
            return Snapshot.failed(Outcome.ERROR, at);
        }
        Snapshot portal = portalError(root, at);
        if (portal != null) {
            return portal;
        }
        JsonNode response = root.path("response");
        String resultCode = response.path("msgHeader").path("resultCode").asText("");
        if ("4".equals(resultCode)) {
            return Snapshot.ok(List.of(), at);
        }
        if (!"0".equals(resultCode)) {
            log.warn("[BusLocation] gyeonggi resultCode={}", resultCode);
            return Snapshot.failed(Outcome.ERROR, at);
        }
        List<Vehicle> vehicles = new ArrayList<>();
        for (JsonNode item : asList(response.path("msgBody").path("busLocationList"))) {
            Integer seq = intOrNull(item.path("stationSeq"));
            String vehId = text(item.path("vehId"));
            if (seq == null || vehId == null) {
                continue;
            }
            vehicles.add(new Vehicle(vehId, text(item.path("plateNo")), seq,
                    Integer.valueOf(2).equals(intOrNull(item.path("stateCd")))));
        }
        return Snapshot.ok(vehicles, at);
    }

    Snapshot parseTago(String body, Instant at) {
        JsonNode root = read(body);
        if (root == null) {
            return Snapshot.failed(Outcome.ERROR, at);
        }
        Snapshot portal = portalError(root, at);
        if (portal != null) {
            return portal;
        }
        JsonNode response = root.path("response");
        String resultCode = response.path("header").path("resultCode").asText("");
        if (!"00".equals(resultCode)) {
            // "99" = 동시 세션 한도(일시) — 차단하지 않는다.
            log.warn("[BusLocation] tago resultCode={}", resultCode);
            return Snapshot.failed("99".equals(resultCode) ? Outcome.LIMITED : Outcome.ERROR, at);
        }
        JsonNode items = response.path("body").path("items");
        JsonNode item = items.isObject() ? items.path("item") : null;
        List<Vehicle> vehicles = new ArrayList<>();
        if (item != null) {
            for (JsonNode one : asList(item)) {
                Integer seq = intOrNull(one.path("nodeord"));
                String plate = text(one.path("vehicleno"));
                if (seq == null || plate == null) {
                    continue;
                }
                vehicles.add(new Vehicle(plate, plate, seq, false));
            }
        }
        return Snapshot.ok(vehicles, at);
    }

    private Snapshot portalError(JsonNode root, Instant at) {
        JsonNode header = root.path("OpenAPI_ServiceResponse").path("cmmMsgHeader");
        if (header.isMissingNode()) {
            return null;
        }
        String reason = header.path("returnReasonCode").asText("");
        log.warn("[BusLocation] portal error reasonCode={}", reason);
        return Snapshot.failed(BLOCKING_REASON_CODES.contains(reason) ? Outcome.BLOCKED : Outcome.ERROR, at);
    }

    private JsonNode read(String body) {
        try {
            return body == null ? null : objectMapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** 배열이면 그대로, 객체 하나면 하나짜리, 그 밖(빈 문자열·누락)은 빈 목록. */
    private static List<JsonNode> asList(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return out;
        }
        if (node.isArray()) {
            node.forEach(out::add);
        } else if (node.isObject()) {
            out.add(node);
        }
        return out;
    }

    private static Integer intOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.intValue();
        }
        try {
            String s = node.asText("").trim();
            return s.isEmpty() ? null : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String s = node.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
