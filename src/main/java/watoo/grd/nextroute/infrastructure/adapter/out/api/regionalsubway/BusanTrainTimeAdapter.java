package watoo.grd.nextroute.infrastructure.adapter.out.api.regionalsubway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDayType;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparture;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalStationSeed;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalSourceException;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalTimetableSource;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalStationSeedCatalog;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalTimetableText;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * 부산교통공사 열차시각표 조회 서비스(data.go.kr 15158990) — 부산 1~4호선.
 *
 * <p>실측(2026-11-16):
 * <ul>
 *   <li>{@code day}·{@code updown}·{@code stime}·{@code etime} 파라미터를 <b>무시</b>하고 역 전체를 준다(서면1 1008행).
 *       그래서 한 번에 받고 행의 {@code dayType}(1 평일, 2 토요일, 3 공휴일)으로 나눈다.</li>
 *   <li>{@code endcode} = 종착역 scode(1호선 95 다대포해수욕장, 134 노포). 다대포 연장 5역은 scode 95~99(TAGO 는 195~199).</li>
 *   <li>시각은 {@code arrtime} 하나뿐이라 출발 시각으로 쓴다.</li>
 * </ul>
 */
@Component
public class BusanTrainTimeAdapter implements RegionalTimetableSource {

    static final int PAGE_SIZE = 1500;
    private static final Set<String> LINES = Set.of("BS1", "BS2", "BS3", "BS4");

    /**
     * 종착 코드가 역 코드와 다른 경우(2026-11-16 운영 적재 실측). 3호선 대저행은 {@code endcode 318}(대저 scode 317),
     * 4호선 안평행은 {@code 415}(안평 414). 반여농산물시장(409)의 종착 코드가 401·415 둘뿐이라 415 = 안평이다.
     * 1·2호선은 역 코드 그대로(95 다대포해수욕장, 134 노포, 201 장산, 243 양산).
     */
    static final java.util.Map<String, java.util.Map<String, String>> ENDCODE_ALIASES = java.util.Map.of(
            "BS3", java.util.Map.of("318", "317"),
            "BS4", java.util.Map.of("415", "414"));

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final RegionalStationSeedCatalog catalog;
    private final String baseUrl;
    private final String key;

    public BusanTrainTimeAdapter(RestClient restClient,
                                 ObjectMapper objectMapper,
                                 RegionalStationSeedCatalog catalog,
                                 @Value("${busan.api.train-time-base-url}") String baseUrl,
                                 @Value("${busan.api.key:}") String key) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.catalog = catalog;
        this.baseUrl = baseUrl;
        this.key = key;
    }

    @Override
    public Set<String> lineCodes() {
        return LINES;
    }

    @Override
    public String sourceCode() {
        return "BUSAN_API";
    }

    @Override
    public List<RegionalDeparture> fetch(RegionalStationSeed station) {
        DataGoKrJson.requireKey(key);
        if (station.providerStationCode() == null) {
            throw RegionalSourceException.failed("no scode for " + station.stationKey());
        }
        JsonNode root = DataGoKrJson.call(restClient, objectMapper, URI.create(baseUrl
                + "/getTrainTime?serviceKey=" + key + "&act=json&scode=" + station.providerStationCode()
                + "&numOfRows=" + PAGE_SIZE + "&pageNo=1"));
        JsonNode header = root.path("header");
        String resultCode = header.path("resultCode").asText("");
        if (!"00".equals(resultCode)) {
            throw RegionalSourceException.failed("busan resultCode=" + resultCode + " " + header.path("resultMsg").asText(""));
        }
        JsonNode body = root.path("body");
        long total = body.path("totalCount").asLong(0);
        if (total > PAGE_SIZE) {
            throw RegionalSourceException.failed("busan totalCount=" + total + " > page " + PAGE_SIZE);
        }
        List<JsonNode> raw = DataGoKrJson.items(body, i -> i);
        if (raw.size() < total) {
            // 서버가 numOfRows 를 깎아 일부만 주면 완전한 역처럼 덮어쓰지 않는다(옛 값 유지)
            throw RegionalSourceException.failed("busan partial page " + raw.size() + "/" + total);
        }
        return raw.stream().map(i -> toDeparture(i, station)).filter(Objects::nonNull).toList();
    }

    RegionalDeparture toDeparture(JsonNode i, RegionalStationSeed station) {
        RegionalDayType dayType = switch (i.path("dayType").asText("")) {
            case "1" -> RegionalDayType.WD;
            case "2" -> RegionalDayType.SA;
            case "3" -> RegionalDayType.HO;
            default -> null;
        };
        OptionalInt seconds = RegionalTimetableText.serviceSeconds(i.path("arrtime").asText(null));
        if (dayType == null || seconds.isEmpty()) {
            return null;
        }
        String rawEndcode = i.path("endcode").asText("");
        String endcode = ENDCODE_ALIASES.getOrDefault(station.lineCode(), java.util.Map.of()).getOrDefault(rawEndcode, rawEndcode);
        RegionalStationSeed terminal = catalog.findByProviderCode(station.lineCode(), endcode);
        // 못 찾으면 코드를 이름 자리에 둔다 → 적재 서비스가 unmatched 로 센다(방향 모르는 열차를 넣지 않는다)
        String terminalName = terminal != null ? terminal.stationName() : "#scode:" + endcode;
        return new RegionalDeparture(dayType, seconds.getAsInt(), terminalName);
    }
}
