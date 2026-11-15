package watoo.grd.nextroute.infrastructure.adapter.out.api.tago;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.arrival.dto.TagoBusArrival;
import watoo.grd.nextroute.application.arrival.dto.TagoStopArrival;
import watoo.grd.nextroute.application.arrival.port.out.TagoStopArrivalPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.infrastructure.adapter.out.api.ApiCallBlocker;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RegionalBusShared;

/**
 * TAGO 정류장 단위 도착(#577) {@code getSttnAcctoArvlPrearngeInfoList}: 정류장에 오는 버스 전부(버스 한 대 = 한 줄).
 *
 * <ul>
 *   <li>동시 세션은 노선 단위 TAGO 와 같은 서버 합산 세마포어({@code tago-bus})를 나눠 쓴다(한 번이 노선 단위 여러 번을
 *       대신하므로 오히려 줄어든다).</li>
 *   <li>한도 초과·키 오류는 이 기능만 자정까지 막는다(차단 이름 {@value #API_NAME}).</li>
 *   <li>한 쪽 {@value #NUM_OF_ROWS}줄. {@code totalCount} 가 넘으면 다음 쪽(최대 {@value #MAX_PAGES}쪽). 다음 쪽은
 *       {@value #PAGE_BUDGET_MS} ms 안에서만 연다. 다음 쪽이 실패하거나 시간이 넘으면 앞 쪽만 성공으로 주지 않고 실패로
 *       돌려준다(일부만 캐시되어 노선이 30초 빠지지 않게).</li>
 *   <li>HTTP 는 서울 검색과 같은 {@code busRealtimeRestClient}(1.5 s/1.5 s). 요청 기한(2.5 s)을 크게 넘겨 자리를 잡고 있지
 *       않게 한다.</li>
 *   <li>공유 부품이 켜져 있으면 쪽마다 일일 몫({@value #QUOTA_BUCKET})을 자리를 잡은 뒤에 센다.</li>
 * </ul>
 */
@Slf4j
@Component
public class TagoStopArrivalAdapter implements TagoStopArrivalPort {

    static final String API_NAME = "tago-stop-arrival";
    static final int NUM_OF_ROWS = 100;
    static final int MAX_PAGES = 3;
    static final String QUOTA_BUCKET = "tago-stop-arrival";
    static final long PAGE_BUDGET_MS = 2500;
    private static final long PERMIT_WAIT_MS = 300;
    private static final Set<String> BLOCKING_REASON_CODES = Set.of("20", "22", "30", "31", "32", "33");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ApiCallBlocker blocker;
    private final String baseUrl;
    private final String apiKey;
    private final RegionalBusShared shared;
    private final TagoBusArrivalAdapter routeAdapter;

    public TagoStopArrivalAdapter(@Qualifier("busRealtimeRestClient") RestClient restClient,
                                  ObjectMapper objectMapper,
                                  ApiCallBlocker blocker,
                                  @Value("${tago.api.bus-arrival-base-url}") String baseUrl,
                                  @Value("${tago.api.bus-key:}") String apiKey,
                                  RegionalBusShared shared,
                                  TagoBusArrivalAdapter routeAdapter) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.blocker = blocker;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.shared = shared;
        this.routeAdapter = routeAdapter;
    }

    @Override
    public BusQueryResult<TagoStopArrival> getStopArrivals(String cityCode, String nodeId) {
        if (apiKey == null || apiKey.isBlank()) {
            return BusQueryResult.error();
        }
        if (blocker.isBlocked(API_NAME)) {
            return BusQueryResult.blocked();
        }
        if (shared.enabled()) {
            Optional<String> permit = shared.semaphore().tryAcquire(TagoBusArrivalAdapter.SHARED_SEMAPHORE,
                    shared.props().getTagoMaxConcurrent(), shared.props().getTagoLeaseMs(), PERMIT_WAIT_MS, 50);
            if (permit.isEmpty()) {
                return BusQueryResult.limited();
            }
            try {
                return fetchAll(cityCode, nodeId, true);
            } finally {
                shared.semaphore().release(TagoBusArrivalAdapter.SHARED_SEMAPHORE, permit.get());
            }
        }
        // 공유 부품이 꺼져 있으면 노선 단위 어댑터의 JVM 세마포어를 같이 쓴다(동시 세션 몫은 하나).
        boolean acquired;
        try {
            acquired = routeAdapter.permits.tryAcquire(PERMIT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return BusQueryResult.error();
        }
        if (!acquired) {
            return BusQueryResult.limited();
        }
        try {
            return fetchAll(cityCode, nodeId, false);
        } finally {
            routeAdapter.permits.release();
        }
    }

    private BusQueryResult<TagoStopArrival> fetchAll(String cityCode, String nodeId, boolean countQuota) {
        List<TagoStopArrival> all = new ArrayList<>();
        long startedNanos = System.nanoTime();
        for (int page = 1; page <= MAX_PAGES; page++) {
            if (page > 1 && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos) >= PAGE_BUDGET_MS) {
                log.debug("[TagoStop] page budget exceeded before page {} nodeId={}", page, nodeId);
                return BusQueryResult.limited();
            }
            if (countQuota && !shared.quota().tryAcquire(QUOTA_BUCKET, shared.props().getTagoStopDailyLimit())) {
                log.debug("[TagoStop] daily quota exhausted; nodeId={}", nodeId);
                return BusQueryResult.limited();
            }
            String body;
            try {
                URI uri = URI.create(baseUrl + "/getSttnAcctoArvlPrearngeInfoList"
                        + "?serviceKey=" + apiKey
                        + "&_type=json"
                        + "&pageNo=" + page
                        + "&numOfRows=" + NUM_OF_ROWS
                        + "&cityCode=" + enc(cityCode)
                        + "&nodeId=" + enc(nodeId));
                body = restClient.get().uri(uri).exchange((req, res) ->
                        new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.warn("[TagoStop] call failed cityCode={} nodeId={}: {}", cityCode, nodeId, e.getMessage());
                return BusQueryResult.error();
            }
            Page parsed = parse(body, nodeId);
            if (parsed.result() != null) {
                return parsed.result();
            }
            all.addAll(parsed.rows());
            if (parsed.rows().isEmpty() || all.size() >= parsed.totalCount()) {
                break;
            }
        }
        return BusQueryResult.ok(List.copyOf(all));
    }

    /** {@code result} 가 null 이 아니면 그 쪽은 실패(또는 끝)라 그대로 돌려준다. */
    record Page(BusQueryResult<TagoStopArrival> result, List<TagoStopArrival> rows, int totalCount) {
    }

    Page parse(String body, String nodeId) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("[TagoStop] non-JSON response nodeId={}", nodeId);
            return new Page(BusQueryResult.error(), List.of(), 0);
        }
        if (root == null) {
            return new Page(BusQueryResult.error(), List.of(), 0);
        }
        JsonNode portalError = root.path("OpenAPI_ServiceResponse").path("cmmMsgHeader");
        if (!portalError.isMissingNode()) {
            String reason = portalError.path("returnReasonCode").asText("");
            log.warn("[TagoStop] portal error reasonCode={} msg={}", reason, portalError.path("errMsg").asText(""));
            if (BLOCKING_REASON_CODES.contains(reason)) {
                blocker.block(API_NAME);
                return new Page(BusQueryResult.blocked(), List.of(), 0);
            }
            return new Page(BusQueryResult.error(), List.of(), 0);
        }
        JsonNode response = root.path("response");
        String resultCode = response.path("header").path("resultCode").asText("");
        if (!"00".equals(resultCode)) {
            log.warn("[TagoStop] resultCode={} msg={} nodeId={}", resultCode,
                    response.path("header").path("resultMsg").asText(""), nodeId);
            return new Page(BusQueryResult.error(), List.of(), 0);
        }
        int total = response.path("body").path("totalCount").asInt(0);
        JsonNode items = response.path("body").path("items");
        JsonNode item = items.isObject() ? items.path("item") : null;
        List<TagoStopArrival> rows = new ArrayList<>();
        if (item != null && item.isArray()) {
            item.forEach(i -> rows.add(toRow(i)));
        } else if (item != null && item.isObject()) {
            rows.add(toRow(item));
        }
        return new Page(null, List.copyOf(rows), total);
    }

    private static TagoStopArrival toRow(JsonNode i) {
        return new TagoStopArrival(
                new TagoBusArrival(text(i.path("routeid")), intOrNull(i.path("arrtime")),
                        intOrNull(i.path("arrprevstationcnt")), text(i.path("vehicletp"))),
                text(i.path("routeno")), text(i.path("routetp")));
    }

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
}
