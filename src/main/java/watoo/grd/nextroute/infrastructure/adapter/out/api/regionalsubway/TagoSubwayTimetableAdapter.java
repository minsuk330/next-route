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
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalTagoCrossCheckPort;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalTimetableSource;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalTimetableText;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/**
 * TAGO 도시철도 역별 시간표 — 대전1호선의 주 출처.
 *
 * <p>대전교통공사 공식 엑셀(2026-03-30 판)과 판암역 평일, 주말·휴일 전 시각이 일치했다(2026-11-16).
 * 토요일 코드 02 는 전 노선 0건이라 부르지 않는다(대전은 공식도 주말·휴일 한 표).
 * 대구·광주는 반영이 늦을 수 있어(광주 11-01 추가 열차 누락) 주 출처로 쓰지 않는다.
 *
 * <p>ML 배치용 {@code TagoSubwayApiAdapter} 와 별개다. 그 어댑터는 {@code ApiCallBlocker} 한 번 실패로 하루 막히고
 * 한 건 응답(객체)을 못 읽는다.
 */
@Component
public class TagoSubwayTimetableAdapter implements RegionalTimetableSource, RegionalTagoCrossCheckPort {

    static final int PAGE_SIZE = 500;
    private static final Set<String> LINES = Set.of("DJ1");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String key;

    public TagoSubwayTimetableAdapter(RestClient restClient,
                                      ObjectMapper objectMapper,
                                      @Value("${seoul.api.subway-tago-base-url}") String baseUrl,
                                      @Value("${seoul.api.subway-key:}") String key) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.key = key;
    }

    @Override
    public Set<String> lineCodes() {
        return LINES;
    }

    @Override
    public String sourceCode() {
        return "TAGO";
    }

    @Override
    public List<RegionalDeparture> fetch(RegionalStationSeed station) {
        if (station.tagoStationId() == null) {
            throw RegionalSourceException.failed("no tago id for " + station.stationKey());
        }
        List<RegionalDeparture> out = new ArrayList<>();
        for (String day : List.of("01", "03")) {
            RegionalDayType dayType = "01".equals(day) ? RegionalDayType.WD : RegionalDayType.HO;
            for (String upDown : List.of("U", "D")) {
                out.addAll(page(station.tagoStationId(), day, upDown, dayType));
            }
        }
        return out;
    }

    /** 대구 파일 신선도 비교용. 노선 제한 없이 같은 호출. */
    @Override
    public List<RegionalDeparture> fetchTago(RegionalStationSeed station) {
        return fetch(station);
    }

    private List<RegionalDeparture> page(String tagoStationId, String day, String upDown, RegionalDayType dayType) {
        DataGoKrJson.requireKey(key);
        List<RegionalDeparture> out = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode root = DataGoKrJson.call(restClient, objectMapper, URI.create(baseUrl
                    + "/GetSubwaySttnAcctoSchdulList?serviceKey=" + key
                    + "&pageNo=" + page + "&numOfRows=" + PAGE_SIZE + "&_type=json"
                    + "&subwayStationId=" + tagoStationId
                    + "&dailyTypeCode=" + day + "&upDownTypeCode=" + upDown));
            JsonNode header = root.path("response").path("header");
            String resultCode = header.path("resultCode").asText("");
            if ("99".equals(resultCode)) {
                throw RegionalSourceException.retryable("tago resultCode=99");
            }
            if (!"00".equals(resultCode)) {
                throw RegionalSourceException.failed("tago resultCode=" + resultCode + " " + header.path("resultMsg").asText(""));
            }
            JsonNode body = root.path("response").path("body");
            out.addAll(DataGoKrJson.items(body, i -> toDeparture(i, dayType)).stream()
                    .filter(java.util.Objects::nonNull).toList());
            if ((long) page * PAGE_SIZE >= body.path("totalCount").asLong(0)) {
                return out;
            }
        }
    }

    /** {@code depTime "053200"}. 대전 시청 휴일에 {@code "0"} 이 한 건 있다 → 버린다. */
    static RegionalDeparture toDeparture(JsonNode i, RegionalDayType dayType) {
        String dep = i.path("depTime").asText("");
        if (dep.length() != 6) {
            return null;
        }
        OptionalInt seconds = RegionalTimetableText.serviceSeconds(dep);
        String terminal = i.path("endSubwayStationNm").asText("");
        if (seconds.isEmpty() || terminal.isBlank()) {
            return null;
        }
        return new RegionalDeparture(dayType, seconds.getAsInt(), terminal);
    }
}
