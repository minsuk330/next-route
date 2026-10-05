package watoo.grd.nextroute.infrastructure.adapter.out.api.gyeonggi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.arrival.port.out.GyeonggiBusArrivalPort;
import watoo.grd.nextroute.application.route.concurrent.SingleFlight;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.infrastructure.adapter.out.api.ApiCallBlocker;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 경기도 버스도착 API(공공데이터포털 {@code 6410000/busarrivalservice/v2}) {@code getBusArrivalItemv2}.
 *
 * <p>실측(2026-10-05)에서 정한 규칙:
 * <ul>
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

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final SingleFlight<String, BusQueryResult<GyeonggiBusArrival>> flight = new SingleFlight<>();

    private record CacheEntry(BusQueryResult<GyeonggiBusArrival> result, Instant expiresAt) {}

    public GyeonggiBusArrivalAdapter(RestClient restClient,
                                     ObjectMapper objectMapper,
                                     ApiCallBlocker blocker,
                                     Clock clock,
                                     @Value("${gyeonggi.api.bus-arrival-base-url}") String baseUrl,
                                     @Value("${gyeonggi.api.bus-key:}") String apiKey) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.blocker = blocker;
        this.clock = clock;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public BusQueryResult<GyeonggiBusArrival> getArrival(String stationId, String routeId) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("[GyeonggiBus] key not configured; skip stationId={} routeId={}", stationId, routeId);
            return BusQueryResult.error();
        }
        if (blocker.isBlocked(API_NAME)) {
            return BusQueryResult.blocked();
        }
        String key = stationId + ":" + routeId;
        CacheEntry hit = cache.get(key);
        if (hit != null && clock.instant().isBefore(hit.expiresAt())) {
            return BusQueryResult.cached(hit.result().data());
        }

        SingleFlight.Outcome<BusQueryResult<GyeonggiBusArrival>> outcome =
                flight.execute(key, () -> fetchAndCache(key, stationId, routeId), JOIN_WAIT_MS);
        return outcome.hasValue() ? outcome.value() : BusQueryResult.error();
    }

    private BusQueryResult<GyeonggiBusArrival> fetchAndCache(String key, String stationId, String routeId) {
        BusQueryResult<GyeonggiBusArrival> result = fetch(stationId, routeId);
        // 조회 성공(빈 결과 포함)만 캐시한다. 실패를 캐시하면 일시 오류가 15초 동안 굳는다.
        if (result.isOk()) {
            cache.put(key, new CacheEntry(result, clock.instant().plus(CACHE_TTL)));
            evictExpired();
        }
        return result;
    }

    private BusQueryResult<GyeonggiBusArrival> fetch(String stationId, String routeId) {
        URI uri = URI.create(baseUrl + "/getBusArrivalItemv2"
                + "?serviceKey=" + apiKey
                + "&stationId=" + stationId
                + "&routeId=" + routeId
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
