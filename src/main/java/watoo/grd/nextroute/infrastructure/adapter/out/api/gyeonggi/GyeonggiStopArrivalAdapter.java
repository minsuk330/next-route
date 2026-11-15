package watoo.grd.nextroute.infrastructure.adapter.out.api.gyeonggi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiStopArrival;
import watoo.grd.nextroute.application.arrival.port.out.GyeonggiStopArrivalPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.infrastructure.adapter.out.api.ApiCallBlocker;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RegionalBusShared;

/**
 * 경기 정류장 단위 도착(#577) {@code busarrivalservice/v2/getBusArrivalListv2}: 정류장 하나에 서는 노선 전부를 한 번에.
 *
 * <ul>
 *   <li>공급자 한도가 기능별 10,000 이라 노선 단위({@code getBusArrivalItemv2})와 <b>따로</b> 센다: 버킷
 *       {@value #QUOTA_BUCKET}, 상한 {@code regional-bus.shared.gyeonggi-stop-daily-limit}.</li>
 *   <li>한도 초과·키 오류 응답은 이 기능만 자정까지 막는다(차단 이름 {@value #API_NAME}) — 노선 단위 도착과 안내는 그대로.</li>
 *   <li>포털 키 동시 세션 30 을 경기·TAGO·지하철 TAGO 가 나눠 쓰므로 서버 합산 세마포어 {@value #SHARED_SEMAPHORE} 로
 *       묶는다(공유 부품이 꺼져 있으면 JVM 세마포어).</li>
 *   <li>값이 없으면 {@code ""}, 숫자 칸이 문자일 때도 있다(노선 이름 {@code 99} / {@code "62-1"}).
 *       {@code resultCode} 4 = 결과 없음.</li>
 * </ul>
 * 캐시와 같은 키 묶기는 부르는 쪽({@code BusStopArrivalQueryService})이 맡는다.
 */
@Slf4j
@Component
public class GyeonggiStopArrivalAdapter implements GyeonggiStopArrivalPort {

    static final String API_NAME = "gyeonggi-stop-arrival";
    static final String QUOTA_BUCKET = "gg-stop-arrival";
    static final String SHARED_SEMAPHORE = "gg-bus";
    private static final long PERMIT_WAIT_MS = 300;
    private static final Set<String> BLOCKING_REASON_CODES = Set.of("20", "22", "30", "31", "32", "33");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ApiCallBlocker blocker;
    private final String baseUrl;
    private final String apiKey;
    private final RegionalBusShared shared;
    final Semaphore localPermits;

    public GyeonggiStopArrivalAdapter(@Qualifier("busRealtimeRestClient") RestClient restClient,
                                      ObjectMapper objectMapper,
                                      ApiCallBlocker blocker,
                                      @Value("${gyeonggi.api.bus-arrival-base-url}") String baseUrl,
                                      @Value("${gyeonggi.api.bus-key:}") String apiKey,
                                      RegionalBusShared shared) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.blocker = blocker;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.shared = shared;
        this.localPermits = new Semaphore(Math.max(1, shared.props().getGyeonggiStopMaxConcurrent()));
    }

    @Override
    public BusQueryResult<GyeonggiStopArrival> getStationArrivals(String stationId) {
        if (apiKey == null || apiKey.isBlank()) {
            return BusQueryResult.error();
        }
        if (blocker.isBlocked(API_NAME)) {
            return BusQueryResult.blocked();
        }
        if (shared.enabled()) {
            Optional<String> permit = shared.semaphore().tryAcquire(SHARED_SEMAPHORE,
                    shared.props().getGyeonggiStopMaxConcurrent(), shared.props().getGyeonggiStopLeaseMs(),
                    PERMIT_WAIT_MS, 50);
            if (permit.isEmpty()) {
                return BusQueryResult.limited();
            }
            try {
                // 자리를 잡은 뒤에 센다 — 자리를 못 잡아 부르지 않은 요청이 몫을 태우지 않게.
                if (!shared.quota().tryAcquire(QUOTA_BUCKET, shared.props().getGyeonggiStopDailyLimit())) {
                    log.debug("[GyeonggiStop] daily quota exhausted; stationId={}", stationId);
                    return BusQueryResult.limited();
                }
                return fetch(stationId);
            } finally {
                shared.semaphore().release(SHARED_SEMAPHORE, permit.get());
            }
        }
        boolean acquired;
        try {
            acquired = localPermits.tryAcquire(PERMIT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return BusQueryResult.error();
        }
        if (!acquired) {
            return BusQueryResult.limited();
        }
        try {
            return fetch(stationId);
        } finally {
            localPermits.release();
        }
    }

    private BusQueryResult<GyeonggiStopArrival> fetch(String stationId) {
        URI uri = URI.create(baseUrl + "/getBusArrivalListv2"
                + "?serviceKey=" + apiKey
                + "&stationId=" + stationId
                + "&format=json");
        String body;
        try {
            body = restClient.get().uri(uri).exchange((req, res) ->
                    new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("[GyeonggiStop] call failed stationId={}: {}", stationId, e.getMessage());
            return BusQueryResult.error();
        }
        return parse(body, stationId);
    }

    BusQueryResult<GyeonggiStopArrival> parse(String body, String stationId) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("[GyeonggiStop] non-JSON response stationId={}", stationId);
            return BusQueryResult.error();
        }
        if (root == null) {
            return BusQueryResult.error();
        }
        JsonNode portalError = root.path("OpenAPI_ServiceResponse").path("cmmMsgHeader");
        if (!portalError.isMissingNode()) {
            String reason = portalError.path("returnReasonCode").asText("");
            log.warn("[GyeonggiStop] portal error reasonCode={} msg={}", reason, portalError.path("errMsg").asText(""));
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
            log.warn("[GyeonggiStop] resultCode={} msg={} stationId={}", resultCode,
                    response.path("msgHeader").path("resultMessage").asText(""), stationId);
            return BusQueryResult.error();
        }
        JsonNode list = response.path("msgBody").path("busArrivalList");
        List<GyeonggiStopArrival> rows = new ArrayList<>();
        if (list.isArray()) {
            list.forEach(item -> rows.add(toRow(item)));
        } else if (list.isObject()) {
            rows.add(toRow(list));
        }
        return BusQueryResult.ok(List.copyOf(rows));
    }

    private static GyeonggiStopArrival toRow(JsonNode item) {
        GyeonggiBusArrival arrival = new GyeonggiBusArrival(
                text(item.path("routeId")),
                intOrNull(item.path("predictTime1")),
                intOrNull(item.path("locationNo1")),
                intOrNull(item.path("crowded1")),
                intOrNull(item.path("remainSeatCnt1")),
                intOrNull(item.path("predictTime2")),
                intOrNull(item.path("locationNo2")),
                intOrNull(item.path("crowded2")),
                intOrNull(item.path("remainSeatCnt2")),
                text(item.path("flag")),
                text(item.path("vehId1")),
                text(item.path("vehId2")),
                intOrNull(item.path("predictTimeSec1")),
                intOrNull(item.path("predictTimeSec2")),
                text(item.path("stationNm1")));
        return new GyeonggiStopArrival(arrival, text(item.path("routeName")), intOrNull(item.path("routeTypeCd")),
                text(item.path("routeDestName")));
    }

    /** 숫자 또는 숫자 문자열. {@code ""}·누락·숫자 아님 → null. */
    static Integer intOrNull(JsonNode node) {
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

    /** 숫자로 와도 글자로 읽는다(노선 이름 99). {@code ""}·누락 → null. */
    static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String s = node.asText("").trim();
        return s.isEmpty() ? null : s;
    }
}
