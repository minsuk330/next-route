package watoo.grd.nextroute.infrastructure.adapter.in.api.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 도착 API 가 <b>실데이터</b>를 우리가 구현한 규칙대로 내려 주는지, 배포된 서버와 공급자 원본을 같은 순간에 받아
 * 대조한다(서울 버스, 경기 버스, TAGO 버스, 서울 지하철).
 *
 * <p>단위 테스트는 공급자 응답을 손으로 만든 값으로 본다. 실제 공급자가 칸 이름을 바꾸거나, 서버 사이 공유
 * 캐시에 옛 모양이 남거나, 노선 카탈로그 순번이 공급자 순번과 어긋나면 그 테스트들은 계속 통과한다. 여기서는
 * 원본을 기준(oracle)으로 두고 우리 응답의 각 칸을 맞춰 본다.
 *
 * <ul>
 *   <li>서울 버스: {@code stopsAway = 요청 순번 − 현재 구간 순번}, 도착 문구의 {@code [N번째 전]} 과 같다.
 *       차량 id 는 원본 그대로({@code "0"} 은 없음). 띠 이름은 원본 노선 정류장 목록에서 같은 순번을 고른 것이고,
 *       첫 이름이 원본의 현재 정류장 이름과 같을 때만 있다.</li>
 *   <li>경기 버스: {@code stopsAway = locationNo}, 초는 분과 60초 안일 때만 원본 초. 띠 이름은 원본 노선 정류장
 *       목록(stationSeq) 기준.</li>
 *   <li>TAGO 버스: 남은 초가 짧은 순서로 첫째, 둘째. {@code stopsAway = arrprevstationcnt}. 차량 id 와 띠 이름은
 *       없다.</li>
 *   <li>서울 지하철: 우리 열차가 원본 열차와 (방면, 행선지)로 짝지어지고, 남은 정거장이 도착 코드 규칙을 따른다.</li>
 *   <li>정류장 단위 버스(#577): 원본 정류장 API(서울 getStationByUid, 경기 getBusArrivalListv2, TAGO
 *       getSttnAcctoArvlPrearngeInfoList)의 노선이 우리 응답에 다 있고, 첫 버스 초가 ±45초 안이고, 시간순(null 은 뒤)이다.
 *       두 공급자 정류장은 서울과 경기 출처가 둘 다 나온다. 정류장 캐시는 30초라 다시 받기 전 31초 기다린다.</li>
 * </ul>
 *
 * <h2>돌리려면</h2>
 * <pre>
 *   set -a; . env/api.env; set +a
 *   ARRIVAL_LIVE_BASE_URL=https://api.refit-100.site ./gradlew test --tests '*RealtimeArrivalLiveIT'
 * </pre>
 * <ul>
 *   <li>{@code ARRIVAL_LIVE_BASE_URL} 이 없으면 skip 이다. 키({@code SEOUL_BUS_API}, {@code GONG_API},
 *       {@code SEOUL_SUBWAY_ARRIVAL})가 없는 공급자도 skip 이다. 키는 로그에 남기지 않는다.</li>
 *   <li>버스가 거의 없는 새벽(KST 00~05시)에는 skip 이다.</li>
 *   <li>표본 id 는 2026-11-10 운영 카탈로그에서 고른 값이다. 노선이 개편돼 id 가 없어지면 그 표본은 "버스 없음"으로
 *       세지고, 버스가 있는 표본이 최소 개수에 못 미치면 실패한다 — 표본을 다시 고르라는 신호다.</li>
 *   <li>서버는 공급자 답을 15초 캐시한다. 두 답이 어긋나면 캐시가 지난 뒤 한 번 더 받아 대조한다. 그래도
 *       어긋난 것만 실패로 센다.</li>
 *   <li>경기와 TAGO 키는 하루 한도와 동시 세션을 운영과 나눠 쓴다: 표본마다 몇 번만 부른다.</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "ARRIVAL_LIVE_BASE_URL", matches = "https?://.+")
class RealtimeArrivalLiveIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Pattern NTH_BEFORE = Pattern.compile("\\[(\\d+)번째 전]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** 서버 캐시 15초 + 두 요청 사이 시간. 같은 버스의 남은 초가 이만큼은 어긋날 수 있다. */
    private static final int SECONDS_TOLERANCE = 45;
    private static final Duration CACHE_EXPIRY = Duration.ofSeconds(16);

    private final String baseUrl = trimSlash(System.getenv("ARRIVAL_LIVE_BASE_URL"));
    /**
     * 실패는 아니지만 알아야 할 것: 서울 노선 카탈로그의 정류장 이름이 공급자 지금 목록과 다른 곳(개명). 서울 정적
     * 적재가 꺼져 있어 생긴다. 띠는 카탈로그 이름을 쓰고, 첫 이름이 다르면 이름 대조가 띠를 막는다.
     */
    private static final List<String> WARNINGS = new ArrayList<>();

    @BeforeEach
    void notAtNight() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Seoul"));
        assumeTrue(now.isAfter(LocalTime.of(5, 30)), "새벽에는 버스가 거의 없다");
    }

    // ----------------------------------------------------------------------------------------- 서울 버스

    private record SeoulSample(String routeId, String stopId, int ord) {
    }

    private static final List<SeoulSample> SEOUL = List.of(
            new SeoulSample("100100033", "107000005", 21),
            new SeoulSample("100100022", "100000002", 14),
            new SeoulSample("100100036", "100000018", 16),
            new SeoulSample("100100044", "106000027", 23),
            new SeoulSample("100100044", "106000083", 18),
            new SeoulSample("100100025", "110000265", 23));

    @Test
    void seoulBus() throws Exception {
        String key = env("SEOUL_BUS_API");
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (SeoulSample sample : SEOUL) {
            List<String> problems = compareSeoul(sample, key);
            if (problems != null && !problems.isEmpty()) {
                Thread.sleep(CACHE_EXPIRY.toMillis());
                problems = compareSeoul(sample, key);
            }
            if (problems == null) {
                continue;
            }
            checked++;
            problems.forEach(p -> failures.add("서울 " + sample + ": " + p));
        }
        System.out.println("[RealtimeArrivalLiveIT] 서울 버스: 대조 " + checked + "/" + SEOUL.size() + ", 어긋남 " + failures.size());
        assertThat(failures).isEmpty();
        assertThat(checked).as("버스가 다니는 서울 표본 수").isGreaterThanOrEqualTo(3);
    }

    /** null = 원본에 버스가 없어 볼 것이 없다. */
    private List<String> compareSeoul(SeoulSample s, String key) throws Exception {
        JsonNode ours = first(getJson(baseUrl + "/api/arrivals/bus/" + s.stopId() + "/" + s.routeId()));
        JsonNode raw = first(getJson("http://ws.bus.go.kr/api/rest/arrive/getArrInfoByRoute?serviceKey=" + key
                + "&stId=" + s.stopId() + "&busRouteId=" + s.routeId() + "&ord=" + s.ord() + "&resultType=json")
                .path("msgBody").path("itemList"));
        if (raw == null) {
            return null;
        }
        String rawVehicle1 = seoulVehicle(raw.path("vehId1").asText(null), raw.path("arrmsg1").asText(""));
        List<String> p = new ArrayList<>();
        if (rawVehicle1 == null) {
            if (ours != null) {
                expectNull(p, "vehicleId1", ours.path("vehicleId1"));
                expectNull(p, "stopsAway1", ours.path("stopsAway1"));
                expectNull(p, "approachStops1", ours.path("approachStops1"));
            }
            return p.isEmpty() ? null : p;
        }
        if (ours == null) {
            p.add("원본에 버스가 있는데 우리 응답이 비었다");
            return p;
        }
        int staOrd = raw.path("staOrd").asInt();
        Integer expectedAway1 = seoulAway(staOrd, raw.path("sectOrd1").asInt(0));
        expectEq(p, "vehicleId1", rawVehicle1, text(ours.path("vehicleId1")));
        expectEq(p, "stopsAway1", expectedAway1, integer(ours.path("stopsAway1")));
        expectNear(p, "predictTime1", raw.path("traTime1").asInt(), integer(ours.path("predictTime1")));
        Matcher nth = NTH_BEFORE.matcher(raw.path("arrmsg1").asText(""));
        if (nth.find()) {
            expectEq(p, "stopsAway1 vs 원본 문구 [N번째 전]", Integer.parseInt(nth.group(1)), integer(ours.path("stopsAway1")));
        }
        String rawVehicle2 = seoulVehicle(raw.path("vehId2").asText(null), raw.path("arrmsg2").asText(""));
        expectEq(p, "vehicleId2", rawVehicle2, text(ours.path("vehicleId2")));
        expectEq(p, "stopsAway2", rawVehicle2 == null ? null : seoulAway(staOrd, raw.path("sectOrd2").asInt(0)),
                integer(ours.path("stopsAway2")));
        if (expectedAway1 != null && expectedAway1 >= 1) {
            // 띠 이름은 우리 노선 카탈로그(bus_route_stop)에서 온다. 구현은 그 카탈로그 기준으로 엄격하게 보고,
            // 카탈로그가 공급자 지금 목록과 같은 정류장을 같은 순번에 두는지(id)도 엄격하게 본다. 이름이 바뀐 것만 경고다.
            Map<Integer, String> catalogNames = new HashMap<>();
            Map<Integer, String> catalogIds = new HashMap<>();
            for (JsonNode stop : getJson(baseUrl + "/api/routes/" + s.routeId() + "/stops").path("stops")) {
                catalogNames.put(stop.path("seq").asInt(), stop.path("stopName").asText());
                catalogIds.put(stop.path("seq").asInt(), stop.path("stopId").asText());
            }
            Map<Integer, String> liveNames = new HashMap<>();
            Map<Integer, String> liveIds = new HashMap<>();
            for (JsonNode stop : getJson("http://ws.bus.go.kr/api/rest/busRouteInfo/getStaionByRoute?serviceKey=" + key
                    + "&busRouteId=" + s.routeId() + "&resultType=json").path("msgBody").path("itemList")) {
                liveNames.put(stop.path("seq").asInt(), stop.path("stationNm").asText());
                liveIds.put(stop.path("seq").asInt(), stop.path("station").asText());
            }
            expectEq(p, "카탈로그 순번 " + s.ord() + " 의 정류장", s.stopId(), catalogIds.get(s.ord()));
            for (int seq : stripSeqs(s.ord(), expectedAway1)) {
                expectEq(p, "카탈로그와 공급자의 순번 " + seq + " 정류장 id", liveIds.get(seq), catalogIds.get(seq));
                String catalog = catalogNames.get(seq);
                String live = liveNames.get(seq);
                if (catalog != null && live != null && !sameName(catalog, live)) {
                    String warning = "서울 " + s.routeId() + " 순번 " + seq + " 이름: 우리 카탈로그 '" + catalog + "' / 공급자 '" + live + "'";
                    WARNINGS.add(warning);
                    System.out.println("[RealtimeArrivalLiveIT] WARN " + warning);
                }
            }
            checkStrip(p, s.ord(), expectedAway1, catalogNames, raw.path("stationNm1").asText(null), ours.path("approachStops1"), true);
        } else {
            expectNull(p, "approachStops1", ours.path("approachStops1"));
        }
        return p;
    }

    /** 문구가 운행종료면 차량 id 가 와도 버스가 없다(막차가 정류장에 닿은 순간, 2026-11-16 사용자 결정). */
    private static String seoulVehicle(String raw, String message) {
        return raw == null || raw.isBlank() || "0".equals(raw) || message.contains("운행종료") ? null : raw;
    }

    private static Integer seoulAway(int staOrd, int sectOrd) {
        if (sectOrd <= 0) {
            return null;
        }
        int away = staOrd - sectOrd;
        return away < 0 ? null : away;
    }

    // ----------------------------------------------------------------------------------------- 경기 버스

    private record GyeonggiSample(String routeId, String stopId, int staOrder) {
    }

    private static final List<GyeonggiSample> GYEONGGI = List.of(
            new GyeonggiSample("200000150", "201000107", 22),
            new GyeonggiSample("200000150", "201000244", 12),
            new GyeonggiSample("238000033", "238000268", 24),
            new GyeonggiSample("234001204", "124000360", 23),
            new GyeonggiSample("213000016", "213000262", 12),
            new GyeonggiSample("222000182", "222000657", 25),
            new GyeonggiSample("200000119", "277103321", 24),
            new GyeonggiSample("232000071", "277102735", 13),
            new GyeonggiSample("234001079", "230000139", 14),
            new GyeonggiSample("240000164", "240000491", 17));

    @Test
    void gyeonggiBus() throws Exception {
        String key = env("GONG_API");
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (GyeonggiSample sample : GYEONGGI) {
            List<String> problems = compareGyeonggi(sample, key);
            if (problems != null && !problems.isEmpty()) {
                Thread.sleep(CACHE_EXPIRY.toMillis());
                problems = compareGyeonggi(sample, key);
            }
            if (problems == null) {
                continue;
            }
            checked++;
            problems.forEach(p -> failures.add("경기 " + sample + ": " + p));
        }
        System.out.println("[RealtimeArrivalLiveIT] 경기 버스: 대조 " + checked + "/" + GYEONGGI.size() + ", 어긋남 " + failures.size());
        assertThat(failures).isEmpty();
        assertThat(checked).as("버스가 다니는 경기 표본 수").isGreaterThanOrEqualTo(3);
    }

    private List<String> compareGyeonggi(GyeonggiSample s, String key) throws Exception {
        JsonNode ours = first(getJson(baseUrl + "/api/arrivals/bus/" + s.stopId() + "/" + s.routeId()));
        JsonNode raw = getJson("https://apis.data.go.kr/6410000/busarrivalservice/v2/getBusArrivalItemv2?serviceKey=" + key
                + "&stationId=" + s.stopId() + "&routeId=" + s.routeId() + "&staOrder=" + s.staOrder() + "&format=json")
                .path("response").path("msgBody").path("busArrivalItem");
        Integer rawMinutes1 = integer(raw.path("predictTime1"));
        if (raw.isMissingNode() || rawMinutes1 == null) {
            List<String> p = new ArrayList<>();
            if (ours != null) {
                expectNull(p, "predictTime1", ours.path("predictTime1"));
                expectNull(p, "stopsAway1", ours.path("stopsAway1"));
                expectNull(p, "vehicleId1", ours.path("vehicleId1"));
            }
            return p.isEmpty() ? null : p;
        }
        List<String> p = new ArrayList<>();
        if (ours == null) {
            p.add("원본에 버스가 있는데 우리 응답이 비었다");
            return p;
        }
        Integer away1 = nonNegative(integer(raw.path("locationNo1")));
        expectEq(p, "stopsAway1", away1, integer(ours.path("stopsAway1")));
        expectEq(p, "vehicleId1", gyeonggiVehicle(raw.path("vehId1")), text(ours.path("vehicleId1")));
        expectNear(p, "predictTime1", gyeonggiSeconds(rawMinutes1, integer(raw.path("predictTimeSec1"))),
                integer(ours.path("predictTime1")));
        Integer rawMinutes2 = integer(raw.path("predictTime2"));
        expectEq(p, "vehicleId2", rawMinutes2 == null ? null : gyeonggiVehicle(raw.path("vehId2")), text(ours.path("vehicleId2")));
        expectEq(p, "stopsAway2", rawMinutes2 == null ? null : nonNegative(integer(raw.path("locationNo2"))),
                integer(ours.path("stopsAway2")));
        String busStopName = raw.path("stationNm1").asText("");
        if (away1 != null && away1 >= 1 && !busStopName.isBlank()) {
            Map<Integer, String> names = new HashMap<>();
            for (JsonNode stop : getJson("https://apis.data.go.kr/6410000/busrouteservice/v2/getBusRouteStationListv2?serviceKey="
                    + key + "&routeId=" + s.routeId() + "&format=json")
                    .path("response").path("msgBody").path("busRouteStationList")) {
                names.put(stop.path("stationSeq").asInt(), stop.path("stationName").asText());
            }
            checkStrip(p, s.staOrder(), away1, names, busStopName, ours.path("approachStops1"), false);
        } else {
            expectNull(p, "approachStops1", ours.path("approachStops1"));
        }
        return p;
    }

    private static String gyeonggiVehicle(JsonNode node) {
        String raw = node.isMissingNode() || node.isNull() ? null : node.asText();
        return raw == null || raw.isBlank() || "0".equals(raw) ? null : raw;
    }

    private static Integer gyeonggiSeconds(int minutes, Integer sourceSeconds) {
        int fromMinutes = minutes * 60;
        return sourceSeconds != null && sourceSeconds >= 0 && Math.abs(sourceSeconds - fromMinutes) <= 60
                ? sourceSeconds : fromMinutes;
    }

    // ----------------------------------------------------------------------------------------- TAGO 버스

    private record TagoSample(String routeId, String cityCode, String stopId) {
    }

    /** 배차가 잦은 부산(21), 대전(25) 간선과, 2026-11-10 낮에 버스가 있던 울산(26) 노선. */
    private static final List<TagoSample> TAGO = List.of(
            new TagoSample("BSB5201001000", "21", "BSB199080301"),
            new TagoSample("BSB5201003000", "21", "BSB185760201"),
            new TagoSample("BSB5200010000", "21", "BSB193520201"),
            new TagoSample("BSB5200017000", "21", "BSB211380304"),
            new TagoSample("BSB5200027000", "21", "BSB180750302"),
            new TagoSample("BSB5200086000", "21", "BSB505880000"),
            new TagoSample("BSB5200110100", "21", "BSB166440101"),
            new TagoSample("DJB30300037", "25", "DJB8002315"),
            new TagoSample("DJB30300040", "25", "DJB8002338"),
            new TagoSample("DJB30300041", "25", "DJB8002648"),
            new TagoSample("DJB30300071", "25", "DJB8001409"),
            new TagoSample("USB195000178", "26", "USB196020809"),
            new TagoSample("USB196000271", "26", "USB193065021"));

    @Test
    void tagoBus() throws Exception {
        String key = env("GONG_API");
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (TagoSample sample : TAGO) {
            List<String> problems = compareTago(sample, key);
            if (problems != null && !problems.isEmpty()) {
                Thread.sleep(CACHE_EXPIRY.toMillis());
                problems = compareTago(sample, key);
            }
            if (problems == null) {
                continue;
            }
            checked++;
            problems.forEach(p -> failures.add("TAGO " + sample + ": " + p));
        }
        System.out.println("[RealtimeArrivalLiveIT] TAGO 버스: 대조 " + checked + "/" + TAGO.size() + ", 어긋남 " + failures.size());
        assertThat(failures).isEmpty();
        assertThat(checked).as("버스가 다니는 TAGO 표본 수").isGreaterThanOrEqualTo(3);
    }

    private List<String> compareTago(TagoSample s, String key) throws Exception {
        JsonNode ours = first(getJson(baseUrl + "/api/arrivals/bus/" + s.stopId() + "/" + s.routeId()));
        JsonNode items = getJson("https://apis.data.go.kr/1613000/ArvlInfoInqireService/"
                + "getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList?serviceKey=" + key + "&_type=json&pageNo=1&numOfRows=20"
                + "&cityCode=" + s.cityCode() + "&nodeId=" + enc(s.stopId()) + "&routeId=" + enc(s.routeId()))
                .path("response").path("body").path("items").path("item");
        List<JsonNode> buses = new ArrayList<>();
        if (items.isArray()) {
            items.forEach(buses::add);
        } else if (items.isObject()) {
            buses.add(items);
        }
        buses.removeIf(b -> integer(b.path("arrtime")) == null);
        buses.sort((a, b) -> Integer.compare(a.path("arrtime").asInt(), b.path("arrtime").asInt()));
        List<String> p = new ArrayList<>();
        if (buses.isEmpty()) {
            if (ours != null && integer(ours.path("predictTime1")) != null) {
                p.add("원본에 버스가 없는데 우리 응답에 시간이 있다: " + ours.path("predictTime1"));
            }
            return p.isEmpty() ? null : p;
        }
        if (ours == null) {
            p.add("원본에 버스가 있는데 우리 응답이 비었다");
            return p;
        }
        JsonNode firstBus = buses.get(0);
        expectNear(p, "predictTime1", firstBus.path("arrtime").asInt(), integer(ours.path("predictTime1")));
        expectEq(p, "stopsAway1", nonNegative(integer(firstBus.path("arrprevstationcnt"))), integer(ours.path("stopsAway1")));
        if (buses.size() > 1) {
            JsonNode second = buses.get(1);
            expectNear(p, "predictTime2", second.path("arrtime").asInt(), integer(ours.path("predictTime2")));
            expectEq(p, "stopsAway2", nonNegative(integer(second.path("arrprevstationcnt"))), integer(ours.path("stopsAway2")));
        }
        expectNull(p, "vehicleId1", ours.path("vehicleId1"));
        expectNull(p, "approachStops1", ours.path("approachStops1"));
        return p;
    }

    // ----------------------------------------------------------------------------------------- 정류장 단위 버스(#577)

    /** arsId 가 있으면 서울, gg 면 경기, tagoCity 가 있으면 TAGO 를 원본으로 부른다. 두 공급자 정류장은 둘 다. */
    private record StopSample(String stopId, String arsId, boolean gg, String tagoCity) {
    }

    /** 2026-11-16 운영 카탈로그 기준. 121000009 = 서울 노선 + 경기 43노선, 124000360 = 서울 341·370 + 경기 9노선. */
    private static final List<StopSample> STOPS = List.of(
            new StopSample("121000091", "22167", false, null),
            new StopSample("121000009", "22009", true, null),
            new StopSample("124000360", "25005", true, null),
            new StopSample("201000107", null, true, null),
            new StopSample("BSB199080301", null, false, "21"),
            new StopSample("ICB169000096", null, false, "23"));

    private static final Duration STOP_CACHE_EXPIRY = Duration.ofSeconds(31);

    @Test
    void busStopArrivals() throws Exception {
        String seoulKey = env("SEOUL_BUS_API");
        String gongKey = env("GONG_API");
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (StopSample sample : STOPS) {
            List<String> problems = compareStop(sample, seoulKey, gongKey);
            if (problems != null && !problems.isEmpty()) {
                Thread.sleep(STOP_CACHE_EXPIRY.toMillis());
                problems = compareStop(sample, seoulKey, gongKey);
            }
            if (problems == null) {
                continue;
            }
            checked++;
            problems.forEach(p -> failures.add("정류장 " + sample.stopId() + ": " + p));
        }
        System.out.println("[RealtimeArrivalLiveIT] 정류장 단위: 대조 " + checked + "/" + STOPS.size() + ", 어긋남 " + failures.size());
        assertThat(failures).isEmpty();
        assertThat(checked).as("버스가 다니는 정류장 표본 수").isGreaterThanOrEqualTo(4);
    }

    @Test
    void busStopArrivalsBatch() throws Exception {
        List<String> ids = STOPS.stream().map(StopSample::stopId).toList();
        JsonNode body = getJson(baseUrl + "/api/bus/stops/arrivals?stopIds=" + enc(String.join(",", ids)));
        List<String> order = new ArrayList<>();
        List<String> notOk = new ArrayList<>();
        for (JsonNode stop : body) {
            order.add(stop.path("stopId").asText());
            if (!"OK".equals(stop.path("status").asText())) {
                notOk.add(stop.path("stopId").asText() + "=" + stop.path("status").asText());
            }
        }
        assertThat(order).as("요청 순서").isEqualTo(ids);
        assertThat(notOk).as("정류장마다 status").isEmpty();
    }

    /** null = 어느 원본에도 버스가 없어 볼 것이 없다. */
    private List<String> compareStop(StopSample s, String seoulKey, String gongKey) throws Exception {
        // 원본: 노선 id -> 공급자별 첫 버스 초(버스가 없으면 null). 두 공급자가 같은 노선을 주면 둘 다 둔다.
        Map<String, List<Integer>> raw = new HashMap<>();
        Map<String, String> sources = new HashMap<>();
        if (s.arsId() != null) {
            for (JsonNode row : array(getJson("http://ws.bus.go.kr/api/rest/stationinfo/getStationByUid?serviceKey="
                    + seoulKey + "&arsId=" + s.arsId() + "&resultType=json").path("msgBody").path("itemList"))) {
                if (!s.stopId().equals(row.path("stId").asText())) {
                    continue;
                }
                Integer seconds = seoulVehicle(row.path("vehId1").asText(null), row.path("arrmsg1").asText("")) == null
                        ? null : integer(row.path("traTime1"));
                addRaw(raw, row.path("busRouteId").asText(), seconds);
                sources.merge(row.path("busRouteId").asText(), "SEOUL", (a, b) -> a);
            }
        }
        if (s.gg()) {
            for (JsonNode row : array(getJson("https://apis.data.go.kr/6410000/busarrivalservice/v2/getBusArrivalListv2?serviceKey="
                    + gongKey + "&stationId=" + s.stopId() + "&format=json")
                    .path("response").path("msgBody").path("busArrivalList"))) {
                Integer minutes = integer(row.path("predictTime1"));
                addRaw(raw, row.path("routeId").asText(),
                        minutes == null ? null : gyeonggiSeconds(minutes, integer(row.path("predictTimeSec1"))));
                sources.merge(row.path("routeId").asText(), "GG", (a, b) -> a);
            }
        }
        if (s.tagoCity() != null) {
            for (int page = 1; page <= 3; page++) {
                JsonNode body = getJson("https://apis.data.go.kr/1613000/ArvlInfoInqireService/"
                        + "getSttnAcctoArvlPrearngeInfoList?serviceKey=" + gongKey + "&_type=json&numOfRows=100&pageNo=" + page
                        + "&cityCode=" + s.tagoCity() + "&nodeId=" + enc(s.stopId())).path("response").path("body");
                for (JsonNode bus : array(body.path("items").path("item"))) {
                    addRaw(raw, bus.path("routeid").asText(), integer(bus.path("arrtime")));
                    sources.merge(bus.path("routeid").asText(), "TAGO", (a, b) -> a);
                }
                if (page * 100 >= body.path("totalCount").asInt(0)) {
                    break;
                }
            }
        }
        boolean anyBus = raw.values().stream().anyMatch(times -> times.stream().anyMatch(Objects::nonNull));
        JsonNode ours = getJson(baseUrl + "/api/bus/stops/" + enc(s.stopId()) + "/arrivals");
        List<String> p = new ArrayList<>();
        Map<String, JsonNode> oursByRoute = new HashMap<>();
        Integer previous = null;
        boolean sawNull = false;
        for (JsonNode row : ours) {
            String routeId = row.path("routeId").asText();
            if (oursByRoute.put(routeId, row) != null) {
                p.add("노선 " + routeId + " 이 두 줄");
            }
            Integer t = integer(row.path("predictTime1"));
            if (t == null) {
                sawNull = true;
            } else if (sawNull || (previous != null && t < previous)) {
                p.add("정렬: " + routeId + " 의 " + t + " 초가 앞 줄보다 앞서야 한다");
            } else {
                previous = t;
            }
            if (text(row.path("routeTypeSource")) == null) {
                p.add("노선 " + routeId + " routeTypeSource 없음");
            }
        }
        if (!anyBus) {
            for (JsonNode row : ours) {
                if (integer(row.path("predictTime1")) != null) {
                    p.add("원본에 버스가 없는데 " + row.path("routeId").asText() + " 에 시간이 있다");
                }
            }
            return p.isEmpty() ? null : p;
        }
        for (Map.Entry<String, List<Integer>> entry : raw.entrySet()) {
            String routeId = entry.getKey();
            JsonNode row = oursByRoute.get(routeId);
            if (row == null) {
                p.add("원본(" + sources.get(routeId) + ") 노선 " + routeId + " 이 우리 응답에 없다");
                continue;
            }
            Integer best = entry.getValue().stream().filter(Objects::nonNull).min(Integer::compare).orElse(null);
            Integer actual = integer(row.path("predictTime1"));
            if (best == null) {
                if (actual != null && entry.getValue().size() == 1) {
                    p.add("노선 " + routeId + " 원본엔 버스가 없는데 " + actual + " 초");
                }
                continue;
            }
            boolean near = actual != null && entry.getValue().stream()
                    .anyMatch(t -> t != null && Math.abs(t - actual) <= SECONDS_TOLERANCE);
            if (!near) {
                p.add("노선 " + routeId + " predictTime1 기대 " + entry.getValue() + "±" + SECONDS_TOLERANCE + " 실제 " + actual);
            }
        }
        if (s.arsId() != null && s.gg()) {
            List<String> seen = new ArrayList<>();
            ours.forEach(row -> seen.add(row.path("routeTypeSource").asText()));
            if (!seen.contains("SEOUL") || !seen.contains("GG")) {
                p.add("두 공급자 정류장인데 출처가 " + new java.util.TreeSet<>(seen));
            }
        }
        return p;
    }

    private static void addRaw(Map<String, List<Integer>> raw, String routeId, Integer seconds) {
        if (routeId == null || routeId.isBlank()) {
            return;
        }
        List<Integer> times = raw.computeIfAbsent(routeId, k -> new ArrayList<>());
        // TAGO 는 버스마다 한 줄이라 같은 노선이 여러 번 온다. 우리 첫 버스가 그중 하나와 가까우면 맞다고 본다.
        times.add(seconds);
    }

    private static List<JsonNode> array(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(out::add);
        } else if (node.isObject()) {
            out.add(node);
        }
        return out;
    }

    // ----------------------------------------------------------------------------------------- 서울 지하철

    private record SubwayStation(String name, String lineId, double lat, double lon) {
    }

    private static final List<SubwayStation> SUBWAY = List.of(
            new SubwayStation("강남", "1002", 37.49808633653005, 127.02800140627488),
            new SubwayStation("잠실", "1002", 37.51331105877401, 127.10023101886318),
            new SubwayStation("고속터미널", "1003", 37.50454885557265, 127.00512100872538),
            new SubwayStation("사당", "1004", 37.4775912070902, 126.98169851997),
            new SubwayStation("여의도", "1005", 37.5217753947299, 126.924397990207),
            new SubwayStation("신도림", "1001", 37.508908482648, 126.891312500851));

    @Test
    void seoulSubway() throws Exception {
        String key = env("SEOUL_SUBWAY_ARRIVAL");
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (SubwayStation station : SUBWAY) {
            List<String> problems = compareSubway(station, key);
            if (problems != null && !problems.isEmpty()) {
                // 우리 답은 15초마다 모으는 스냅샷이다. 한 번 더 모일 때까지 기다렸다가 다시 본다.
                Thread.sleep(CACHE_EXPIRY.toMillis());
                problems = compareSubway(station, key);
            }
            if (problems == null) {
                continue;
            }
            checked++;
            problems.forEach(p -> failures.add("지하철 " + station.name() + "(" + station.lineId() + "): " + p));
        }
        System.out.println("[RealtimeArrivalLiveIT] 서울 지하철: 대조 " + checked + "/" + SUBWAY.size() + ", 어긋남 " + failures.size());
        assertThat(failures).isEmpty();
        assertThat(checked).as("열차가 다니는 지하철 표본 수").isGreaterThanOrEqualTo(4);
    }

    private List<String> compareSubway(SubwayStation st, String key) throws Exception {
        JsonNode ours = getJson(baseUrl + "/api/arrivals/subway?lat=" + st.lat() + "&lon=" + st.lon() + "&line_id=" + st.lineId());
        JsonNode rawList = getJson("http://swopenAPI.seoul.go.kr/api/subway/" + key + "/json/realtimeStationArrival/0/40/"
                + enc(st.name())).path("realtimeArrivalList");
        List<JsonNode> raw = new ArrayList<>();
        rawList.forEach(t -> {
            if (st.lineId().equals(t.path("subwayId").asText())) {
                raw.add(t);
            }
        });
        if (raw.isEmpty()) {
            return null;
        }
        List<String> p = new ArrayList<>();
        if (!ours.isArray() || ours.isEmpty()) {
            p.add("원본에 열차가 " + raw.size() + "대 있는데 우리 응답이 비었다");
            return p;
        }
        int matched = 0;
        for (JsonNode train : ours) {
            if (!st.lineId().equals(train.path("lineId").asText())) {
                p.add("요청한 노선이 아닌 열차: lineId=" + train.path("lineId").asText());
                continue;
            }
            Integer expectedAway = stationsAway(train.path("arrivalCode").asText(null), train.path("currentMessage").asText(""));
            expectEq(p, "stationsAway(" + train.path("currentMessage").asText() + ")", expectedAway, integer(train.path("stationsAway")));
            boolean pair = raw.stream().anyMatch(t ->
                    Objects.equals(t.path("updnLine").asText(), train.path("direction").asText())
                            && Objects.equals(t.path("bstatnNm").asText(), train.path("destinationName").asText()));
            if (pair) {
                matched++;
            }
        }
        // 15초 스냅샷과 지금 답은 열차 한두 대가 다를 수 있다. 절반도 짝이 안 맞으면 다른 역이나 다른 방면을 읽고 있다.
        if (matched * 2 < ours.size()) {
            p.add("원본 열차와 (방면, 행선지)가 맞는 열차가 " + matched + "/" + ours.size() + "대뿐이다");
        }
        return p;
    }

    /** SubwayStationsAway.resolve 와 같은 규칙. */
    private static Integer stationsAway(String code, String message) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "0", "1" -> 0;
            case "3", "4", "5" -> 1;
            case "99" -> {
                Matcher m = Pattern.compile("\\[(\\d+)]번째 전역").matcher(message);
                yield m.find() ? Integer.valueOf(m.group(1)) : null;
            }
            default -> null;
        };
    }

    // ----------------------------------------------------------------------------------------- 띠 이름

    /**
     * 띠 = 원본 노선 정류장 목록에서 (요청 순번 − 남은 정거장)부터 요청 순번 앞까지, 네 곳 이상이면 처음과 마지막
     * 두 곳. 첫 이름이 원본의 "버스가 지금 있는 정류장" 이름과 같을 때만 준다(서울은 그 이름이 없으면 대조 없이).
     */
    private static void checkStrip(List<String> p, int boardingSeq, int away, Map<Integer, String> namesBySeq,
                                   String busStopName, JsonNode ours, boolean seoul) {
        List<String> expected = new ArrayList<>();
        for (int seq : stripSeqs(boardingSeq, away)) {
            String name = namesBySeq.get(seq);
            if (name == null || name.isBlank()) {
                expectNull(p, "approachStops1(원본 목록에 순번 " + seq + " 없음)", ours);
                return;
            }
            expected.add(name);
        }
        boolean nameless = busStopName == null || busStopName.isBlank();
        boolean shouldHave = (seoul && nameless) || (!nameless && sameName(expected.get(0), busStopName));
        if (!shouldHave) {
            expectNull(p, "approachStops1(첫 이름 " + expected.get(0) + " ≠ 버스 위치 " + busStopName + ")", ours);
            return;
        }
        List<String> actual = new ArrayList<>();
        if (ours.isArray()) {
            ours.forEach(n -> actual.add(n.asText()));
        }
        if (!actual.equals(expected)) {
            p.add("approachStops1 기대 " + expected + " 실제 " + (ours.isArray() ? actual : ours));
        }
    }

    /** BusApproachStopNames.pickSeqs 와 같은 규칙: 세 곳까지는 전부, 넘으면 처음과 마지막 두 곳. */
    private static List<Integer> stripSeqs(int boardingSeq, int away) {
        List<Integer> seqs = new ArrayList<>();
        int first = boardingSeq - away;
        if (away <= 3) {
            for (int seq = first; seq < boardingSeq; seq++) {
                seqs.add(seq);
            }
        } else {
            seqs.add(first);
            seqs.add(boardingSeq - 2);
            seqs.add(boardingSeq - 1);
        }
        return seqs;
    }

    private static boolean sameName(String a, String b) {
        return WHITESPACE.matcher(a).replaceAll("").equals(WHITESPACE.matcher(b).replaceAll(""));
    }

    // ----------------------------------------------------------------------------------------- 도구

    private static String env(String name) {
        String value = System.getenv(name);
        assumeTrue(value != null && !value.isBlank(), name + " 가 없어 이 공급자는 건너뛴다");
        return value;
    }

    private static JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            // URL 에 키가 들어 있다. 실패 메시지에는 경로만 남긴다.
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + URI.create(url).getHost()
                    + URI.create(url).getPath());
        }
        return JSON.readTree(response.body());
    }

    private static JsonNode first(JsonNode node) {
        return node != null && node.isArray() && !node.isEmpty() ? node.get(0) : null;
    }

    private static Integer integer(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        String text = node.asText().trim();
        return text.matches("-?\\d+") ? Integer.valueOf(text) : null;
    }

    private static Integer nonNegative(Integer value) {
        return value == null || value < 0 ? null : value;
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static void expectEq(List<String> p, String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            p.add(field + " 기대 " + expected + " 실제 " + actual);
        }
    }

    private static void expectNull(List<String> p, String field, JsonNode actual) {
        if (actual != null && !actual.isMissingNode() && !actual.isNull()) {
            p.add(field + " 은 없어야 하는데 " + actual);
        }
    }

    private static void expectNear(List<String> p, String field, Integer expected, Integer actual) {
        if (expected == null || actual == null || Math.abs(expected - actual) > SECONDS_TOLERANCE) {
            p.add(field + " 기대 " + expected + "±" + SECONDS_TOLERANCE + " 실제 " + actual);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trimSlash(String url) {
        return url == null ? null : url.replaceAll("/+$", "");
    }
}
