package watoo.grd.nextroute.infrastructure.adapter.out.api.gyeonggi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.arrival.port.out.GyeonggiBusArrivalPort;
import watoo.grd.nextroute.application.arrival.port.out.GyeonggiStopOrderPort;
import org.springframework.beans.factory.annotation.Autowired;
import watoo.grd.nextroute.application.route.concurrent.SingleFlight;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RegionalBusShared;
import java.time.Duration;
import java.util.Optional;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.infrastructure.adapter.out.api.ApiCallBlocker;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 경기도 버스도착 API(공공데이터포털 {@code 6410000/busarrivalservice/v2}) {@code getBusArrivalItemv2}.
 *
 * <p>실측(2026-10-05)에서 정한 규칙:
 * <ul>
 *   <li>{@code staOrder}(노선 안 정류장 순번)가 필수다. 없으면 {@code resultCode} 2 로 거절한다(2026-11-02 부터 실측).
 *       순번이 하나로 정해지지 않으면 호출하지 않고 ERROR 다.</li>
 *   <li>값이 있으면 숫자, 없으면 {@code ""} 로 온다 — 모든 수치를 숫자·문자 둘 다 받아 읽는다.</li>
 *   <li>{@code msgHeader.resultCode} 0 = 정상, 4 = 결과 없음(그 정류장에 그 노선이 없음 — 서울 노선 id 도 4).</li>
 *   <li>포털 공통 오류는 HTTP 403 등과 함께 {@code OpenAPI_ServiceResponse.cmmMsgHeader.returnReasonCode} 로 온다.
 *       한도 초과(22)·키 오류(30~33)·접근 거부(20)는 그날 계속 실패하므로 자정까지 차단한다.</li>
 * </ul>
 *
 * <p>일일 한도가 기능별 10,000 이라 (정류장, 노선)별 15초 캐시 + 같은 키 동시 호출 병합을 둔다.
 * 키는 포털의 <b>Encoding</b> 키(%2F 포함)를 그대로 URI 에 붙인다 — 다시 인코딩하면 키가 깨진다.
 */
@Slf4j
@Component
public class GyeonggiBusArrivalAdapter implements GyeonggiBusArrivalPort {

    static final String API_NAME = "gyeonggi-bus-arrival";
    static final Duration CACHE_TTL = Duration.ofSeconds(15);
    private static final long JOIN_WAIT_MS = 5_000;
    private static final Set<String> BLOCKING_REASON_CODES = Set.of("20", "22", "30", "31", "32", "33");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ApiCallBlocker blocker;
    private final Clock clock;
    private final String baseUrl;
    private final String apiKey;
    /** 서버 사이 공유 부품(PR-4). {@code enabled()} 가 false 면 아래 JVM 캐시 경로 그대로. */
    private final RegionalBusShared shared;
    private final GyeonggiStopOrderPort stopOrders;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final SingleFlight<String, BusQueryResult<GyeonggiBusArrival>> flight = new SingleFlight<>();

    private record CacheEntry(BusQueryResult<GyeonggiBusArrival> result, Instant expiresAt) {}

    /** JVM 전용(시험·공유 부품 없음). */
    public GyeonggiBusArrivalAdapter(RestClient restClient,
                                     ObjectMapper objectMapper,
                                     ApiCallBlocker blocker,
                                     Clock clock,
                                     String baseUrl,
                                     String apiKey,
                                     GyeonggiStopOrderPort stopOrders) {
        this(restClient, objectMapper, blocker, clock, baseUrl, apiKey, RegionalBusShared.disabled(), stopOrders);
    }

    @Autowired
    public GyeonggiBusArrivalAdapter(RestClient restClient,
                                     ObjectMapper objectMapper,
                                     ApiCallBlocker blocker,
                                     Clock clock,
                                     @Value("${gyeonggi.api.bus-arrival-base-url}") String baseUrl,
                                     @Value("${gyeonggi.api.bus-key:}") String apiKey,
                                     RegionalBusShared shared,
                                     GyeonggiStopOrderPort stopOrders) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.blocker = blocker;
        this.clock = clock;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.shared = shared;
        this.stopOrders = stopOrders;
    }

    @Override
    public BusQueryResult<GyeonggiBusArrival> getArrival(String stationId, String routeId) {
        return getArrival(stationId, routeId, true);
    }

    @Override
    public BusQueryResult<GyeonggiBusArrival> getArrival(String stationId, String routeId, boolean chargeQuota) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("[GyeonggiBus] key not configured; skip stationId={} routeId={}", stationId, routeId);
            return BusQueryResult.error();
        }
        if (blocker.isBlocked(API_NAME)) {
            return BusQueryResult.blocked();
        }
        String key = stationId + ":" + routeId;
        if (shared.enabled()) {
            return getArrivalShared(key, stationId, routeId, chargeQuota);
        }
        CacheEntry hit = cache.get(key);
        if (hit != null && clock.instant().isBefore(hit.expiresAt())) {
            return BusQueryResult.cached(hit.result().data());
        }

        SingleFlight.Outcome<BusQueryResult<GyeonggiBusArrival>> outcome =
                flight.execute(key, () -> fetchAndCache(key, stationId, routeId), JOIN_WAIT_MS);
        return outcome.hasValue() ? outcome.value() : BusQueryResult.error();
    }

    /**
     * 서버 사이 공유 경로(PR-4): Redis 캐시 → JVM SingleFlight → 서버 사이 single-flight → (몫 점유) → 공급자. 몫은
     * 캐시 miss 와 double-check 뒤, 공급자 호출 직전에만 센다 — 캐시 hit 가 몫을 태우지 않게(플랜 검토 #7).
     */
    private BusQueryResult<GyeonggiBusArrival> getArrivalShared(String key, String stationId, String routeId,
                                                                boolean chargeQuota) {
        Optional<List<GyeonggiBusArrival>> hit = shared.cache().getGyeonggi(stationId, routeId);
        if (hit.isPresent()) {
            return BusQueryResult.cached(hit.get());
        }
        SingleFlight.Outcome<BusQueryResult<GyeonggiBusArrival>> outcome = flight.execute(key,
                () -> shared.runner("gg").run("gg-arrival", key,
                        () -> fetchAndStore(stationId, routeId, chargeQuota),
                        () -> shared.cache().getGyeonggi(stationId, routeId),
                        GyeonggiBusArrival.class,
                        Duration.ofMillis(shared.props().getGyeonggiLockTtlMs()), JOIN_WAIT_MS),
                JOIN_WAIT_MS);
        return outcome.hasValue() ? outcome.value() : BusQueryResult.error();
    }

    private BusQueryResult<GyeonggiBusArrival> fetchAndStore(String stationId, String routeId, boolean chargeQuota) {
        OptionalInt order = staOrder(stationId, routeId);
        if (order.isEmpty()) {
            return BusQueryResult.error();   // 호출하지 않으니 몫도 쓰지 않는다
        }
        if (chargeQuota && !shared.quota().tryAcquire("gg-arrival", shared.props().getGyeonggiDailyLimit())) {
            log.debug("[GyeonggiBus] daily quota exhausted; stationId={} routeId={}", stationId, routeId);
            return BusQueryResult.limited();
        }
        BusQueryResult<GyeonggiBusArrival> result = fetch(stationId, routeId, order.getAsInt());
        // 조회 성공(빈 결과 포함)만 저장한다 — JVM 캐시와 같은 규칙.
        if (result.isOk()) {
            shared.cache().putGyeonggi(stationId, routeId, result.data());
        }
        return result;
    }

    private BusQueryResult<GyeonggiBusArrival> fetchAndCache(String key, String stationId, String routeId) {
        OptionalInt order = staOrder(stationId, routeId);
        if (order.isEmpty()) {
            return BusQueryResult.error();
        }
        BusQueryResult<GyeonggiBusArrival> result = fetch(stationId, routeId, order.getAsInt());
        // 조회 성공(빈 결과 포함)만 캐시한다. 실패를 캐시하면 일시 오류가 15초 동안 굳는다.
        if (result.isOk()) {
            cache.put(key, new CacheEntry(result, clock.instant().plus(CACHE_TTL)));
            evictExpired();
        }
        return result;
    }

    /** 캐시 miss 때만 부른다. 표를 못 읽으면 순번을 모르는 것과 같다. */
    private OptionalInt staOrder(String stationId, String routeId) {
        try {
            OptionalInt order = stopOrders.orderOf(stationId, routeId);
            if (order.isEmpty()) {
                log.info("[GyeonggiBus] no unique staOrder; skip stationId={} routeId={}", stationId, routeId);
            }
            return order;
        } catch (RuntimeException e) {
            log.warn("[GyeonggiBus] staOrder lookup failed stationId={} routeId={}: {}",
                    stationId, routeId, e.getMessage());
            return OptionalInt.empty();
        }
    }

    private BusQueryResult<GyeonggiBusArrival> fetch(String stationId, String routeId, int staOrder) {
        URI uri = URI.create(baseUrl + "/getBusArrivalItemv2"
                + "?serviceKey=" + apiKey
                + "&stationId=" + stationId
                + "&routeId=" + routeId
                + "&staOrder=" + staOrder
                + "&format=json");
        String body;
        try {
            body = restClient.get().uri(uri).exchange((req, res) ->
                    new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("[GyeonggiBus] call failed stationId={} routeId={}: {}", stationId, routeId, e.getMessage());
            return BusQueryResult.error();
        }
        log.debug("[GyeonggiBus] call stationId={} routeId={}", stationId, routeId);
        return parse(body, stationId, routeId);
    }

    BusQueryResult<GyeonggiBusArrival> parse(String body, String stationId, String routeId) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("[GyeonggiBus] non-JSON response stationId={} routeId={}", stationId, routeId);
            return BusQueryResult.error();
        }
        if (root == null) {
            return BusQueryResult.error();
        }

        JsonNode portalError = root.path("OpenAPI_ServiceResponse").path("cmmMsgHeader");
        if (!portalError.isMissingNode()) {
            String reason = portalError.path("returnReasonCode").asText("");
            log.warn("[GyeonggiBus] portal error reasonCode={} msg={}", reason, portalError.path("errMsg").asText(""));
            if (BLOCKING_REASON_CODES.contains(reason)) {
                blocker.block(API_NAME);
                return BusQueryResult.blocked();
            }
            return BusQueryResult.error();
        }

        JsonNode response = root.path("response");
        String resultCode = response.path("msgHeader").path("resultCode").asText("");
        if ("4".equals(resultCode)) {
            return BusQueryResult.ok(List.of());
        }
        if (!"0".equals(resultCode)) {
            log.warn("[GyeonggiBus] resultCode={} msg={} stationId={} routeId={}", resultCode,
                    response.path("msgHeader").path("resultMessage").asText(""), stationId, routeId);
            return BusQueryResult.error();
        }

        JsonNode item = response.path("msgBody").path("busArrivalItem");
        if (item.isMissingNode() || item.isNull()) {
            return BusQueryResult.ok(List.of());
        }
        return BusQueryResult.ok(List.of(new GyeonggiBusArrival(
                text(item.path("routeId")),
                intOrNull(item.path("predictTime1")),
                intOrNull(item.path("locationNo1")),
                intOrNull(item.path("crowded1")),
                intOrNull(item.path("remainSeatCnt1")),
                intOrNull(item.path("predictTime2")),
                intOrNull(item.path("locationNo2")),
                intOrNull(item.path("crowded2")),
                intOrNull(item.path("remainSeatCnt2")),
                text(item.path("flag")))));
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

    private void evictExpired() {
        Instant now = clock.instant();
        cache.entrySet().removeIf(e -> !now.isBefore(e.getValue().expiresAt()));
    }
}
