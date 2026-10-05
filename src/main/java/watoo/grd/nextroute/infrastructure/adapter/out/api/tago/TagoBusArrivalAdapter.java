package watoo.grd.nextroute.infrastructure.adapter.out.api.tago;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.arrival.dto.TagoBusArrival;
import watoo.grd.nextroute.application.arrival.port.out.TagoBusArrivalPort;
import watoo.grd.nextroute.application.route.concurrent.SingleFlight;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
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
 * 국토교통부 TAGO 버스도착 API {@code getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList}(서울·경기 밖 전용).
 *
 * <p>실측(2026-10-05)에서 정한 규칙:
 * <ul>
 *   <li>버스 한 대 = item 하나. 결과 없으면 {@code "items": ""}, 한 대면 {@code item} 이 배열이 아니라 객체.</li>
 *   <li>{@code header.resultCode} "00" = 정상. "99" 는 동시 세션 한도(30, 같은 포털 키를 GBIS·TAGO 지하철과 공유) —
 *       일시적이라 차단하지 않고 ERROR.</li>
 *   <li>포털 공통 오류({@code OpenAPI_ServiceResponse}) 20/22/30~33 은 그날 계속 실패하므로 자정까지 차단.</li>
 * </ul>
 *
 * <p>세션 한도 때문에 이 어댑터의 동시 호출을 {@value #MAX_CONCURRENT} 개로 묶고, 자리를 못 얻으면 LIMITED.
 * (정류장, 노선)별 15초 캐시(성공만) + 같은 키 동시 호출 병합. 키는 포털 Encoding 키를 그대로 붙인다.
 */
@Slf4j
@Component
public class TagoBusArrivalAdapter implements TagoBusArrivalPort {

    static final String API_NAME = "tago-bus-arrival";
    static final int MAX_CONCURRENT = 8;
    static final Duration CACHE_TTL = Duration.ofSeconds(15);
    private static final long PERMIT_WAIT_MS = 1_000;
    private static final long JOIN_WAIT_MS = 5_000;
    private static final int NUM_OF_ROWS = 20;
    private static final Set<String> BLOCKING_REASON_CODES = Set.of("20", "22", "30", "31", "32", "33");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ApiCallBlocker blocker;
    private final Clock clock;
    private final String baseUrl;
    private final String apiKey;

    final Semaphore permits = new Semaphore(MAX_CONCURRENT);   // package-private: 테스트가 한도 상황을 만든다
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final SingleFlight<String, BusQueryResult<TagoBusArrival>> flight = new SingleFlight<>();

    private record CacheEntry(BusQueryResult<TagoBusArrival> result, Instant expiresAt) {}

    public TagoBusArrivalAdapter(RestClient restClient,
                                 ObjectMapper objectMapper,
                                 ApiCallBlocker blocker,
                                 Clock clock,
                                 @Value("${tago.api.bus-arrival-base-url}") String baseUrl,
                                 @Value("${tago.api.bus-key:}") String apiKey) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.blocker = blocker;
        this.clock = clock;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public BusQueryResult<TagoBusArrival> getArrivals(String cityCode, String nodeId, String routeId) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("[TagoBus] key not configured; skip nodeId={} routeId={}", nodeId, routeId);
            return BusQueryResult.error();
        }
        if (blocker.isBlocked(API_NAME)) {
            return BusQueryResult.blocked();
        }
        String key = cityCode + ":" + nodeId + ":" + routeId;
        CacheEntry hit = cache.get(key);
        if (hit != null && clock.instant().isBefore(hit.expiresAt())) {
            return BusQueryResult.cached(hit.result().data());
        }

        SingleFlight.Outcome<BusQueryResult<TagoBusArrival>> outcome =
                flight.execute(key, () -> fetchAndCache(key, cityCode, nodeId, routeId), JOIN_WAIT_MS);
        return outcome.hasValue() ? outcome.value() : BusQueryResult.error();
    }

    private BusQueryResult<TagoBusArrival> fetchAndCache(String key, String cityCode, String nodeId, String routeId) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(PERMIT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return BusQueryResult.error();
        }
        if (!acquired) {
            log.debug("[TagoBus] concurrency limit reached; nodeId={} routeId={}", nodeId, routeId);
            return BusQueryResult.limited();
        }
        BusQueryResult<TagoBusArrival> result;
        try {
            result = fetch(cityCode, nodeId, routeId);
        } finally {
            permits.release();
        }
        // 조회 성공(빈 결과 포함)만 캐시한다. 실패를 캐시하면 일시 오류가 15초 동안 굳는다.
        if (result.isOk()) {
            cache.put(key, new CacheEntry(result, clock.instant().plus(CACHE_TTL)));
            evictExpired();
        }
        return result;
    }

    private BusQueryResult<TagoBusArrival> fetch(String cityCode, String nodeId, String routeId) {
        // 구미 노선 id 는 하이픈을 포함한다(GMB171-110). 하이픈은 URL 안전하지만 값은 인코딩해 둔다.
        URI uri = URI.create(baseUrl + "/getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList"
                + "?serviceKey=" + apiKey
                + "&_type=json"
                + "&numOfRows=" + NUM_OF_ROWS
                + "&cityCode=" + enc(cityCode)
                + "&nodeId=" + enc(nodeId)
                + "&routeId=" + enc(routeId));
        String body;
        try {
            body = restClient.get().uri(uri).exchange((req, res) ->
                    new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("[TagoBus] call failed nodeId={} routeId={}: {}", nodeId, routeId, e.getMessage());
            return BusQueryResult.error();
        }
        log.debug("[TagoBus] call cityCode={} nodeId={} routeId={}", cityCode, nodeId, routeId);
        return parse(body, nodeId, routeId);
    }

    BusQueryResult<TagoBusArrival> parse(String body, String nodeId, String routeId) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("[TagoBus] non-JSON response nodeId={} routeId={}", nodeId, routeId);
            return BusQueryResult.error();
        }
        if (root == null) {
            return BusQueryResult.error();
        }

        JsonNode portalError = root.path("OpenAPI_ServiceResponse").path("cmmMsgHeader");
        if (!portalError.isMissingNode()) {
            String reason = portalError.path("returnReasonCode").asText("");
            log.warn("[TagoBus] portal error reasonCode={} msg={}", reason, portalError.path("errMsg").asText(""));
            if (BLOCKING_REASON_CODES.contains(reason)) {
                blocker.block(API_NAME);
                return BusQueryResult.blocked();
            }
            return BusQueryResult.error();
        }

        JsonNode response = root.path("response");
        String resultCode = response.path("header").path("resultCode").asText("");
        if (!"00".equals(resultCode)) {
            log.warn("[TagoBus] resultCode={} msg={} nodeId={} routeId={}", resultCode,
                    response.path("header").path("resultMsg").asText(""), nodeId, routeId);
            return BusQueryResult.error();
        }

        JsonNode items = response.path("body").path("items");
        JsonNode item = items.isObject() ? items.path("item") : null;
        if (item == null || item.isMissingNode() || item.isNull()) {
            return BusQueryResult.ok(List.of());   // "items": "" — 다가오는 버스 없음
        }
        List<TagoBusArrival> buses = new ArrayList<>();
        if (item.isArray()) {
            item.forEach(i -> buses.add(toArrival(i)));
        } else {
            buses.add(toArrival(item));
        }
        return BusQueryResult.ok(List.copyOf(buses));
    }

    private static TagoBusArrival toArrival(JsonNode i) {
        return new TagoBusArrival(
                text(i.path("routeid")),
                intOrNull(i.path("arrtime")),
                intOrNull(i.path("arrprevstationcnt")),
                text(i.path("vehicletp")));
    }

    /** 숫자 또는 숫자 문자열. {@code ""}·누락·숫자 아님 → null. */
    private static Integer intOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.intValue();
        }
        String s = node.asText("").trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s);
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

    private void evictExpired() {
        Instant now = clock.instant();
        cache.entrySet().removeIf(e -> !now.isBefore(e.getValue().expiresAt()));
    }
}
