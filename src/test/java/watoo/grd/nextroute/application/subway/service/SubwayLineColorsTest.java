package watoo.grd.nextroute.application.subway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationsAheadResponse;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry;
import watoo.grd.nextroute.application.journey.dto.TrackViews;
import watoo.grd.nextroute.application.nearby.dto.NearbySubwayStationResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.infrastructure.adapter.in.api.subway.dto.RegionalDeparturesResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubwayLineColorsTest {

    /** 운영 OTP 그래프의 지하철(SUBWAY) 노선 이름 전부(2026-11-16 조회). 하나라도 빠지면 그 노선은 그래프 색으로 돌아간다. */
    private static final List<String> OTP_SUBWAY_NAMES = List.of(
            "서울1호선", "서울2호선", "서울3호선", "서울4호선", "서울5호선", "서울6호선", "서울7호선", "서울8호선", "서울9호선",
            "GTX-A", "경강선", "경의중앙선", "경춘선", "공항철도", "서해선", "수인분당선", "신림선", "신분당선",
            "우이신설경전철", "용인경전철", "의정부경전철", "김포도시철도", "인천1호선", "인천2호선",
            "부산1호선", "부산2호선", "부산3호선", "부산4호선", "부산김해경전철", "동해선", "대경선",
            "대구1호선", "대구2호선", "대구3호선", "대전1호선", "광주1호선");

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    @Test
    @DisplayName("운영 OTP 지하철 노선 36개가 전부 표에 있다")
    void everyOtpSubwayNameResolves() {
        assertThat(OTP_SUBWAY_NAMES).hasSize(36).allSatisfy(name ->
                assertThat(SubwayLineColors.byLineName(name)).as(name).get().asString().matches("^#[0-9A-F]{6}$"));
    }

    @Test
    @DisplayName("공식 값: 서울시 노선별 색상값 RGB(그래프 값과 다름)")
    void officialSeoulValues() {
        assertThat(SubwayLineColors.byLineName("서울2호선")).contains("#00A23F");
        assertThat(SubwayLineColors.byLineName("서울6호선")).contains("#7C4932");
        assertThat(SubwayLineColors.byLineName("신분당선")).contains("#B81B30");
        assertThat(SubwayLineColors.byLineName("인천1호선")).contains("#B4C7E7");
    }

    @Test
    @DisplayName("키: 서울 실시간 id, 표시 이름 별칭, 지방 노선 코드, 공백 무시. 표 밖 노선은 빈 값")
    void keys() {
        assertThat(SubwayLineColors.byLineId("1002")).contains("#00A23F");
        assertThat(SubwayLineColors.byLineId("1077")).contains("#B81B30");
        assertThat(SubwayLineColors.byLineId("1032")).isEqualTo(SubwayLineColors.byLineName("GTX-A"));
        assertThat(SubwayLineColors.byLineName("2호선")).contains("#00A23F");
        assertThat(SubwayLineColors.byLineName("김포골드라인")).isEqualTo(SubwayLineColors.byLineName("김포도시철도"));
        assertThat(SubwayLineColors.byLineName("에버라인")).isEqualTo(SubwayLineColors.byLineName("용인경전철"));
        assertThat(SubwayLineColors.byLineName("BS2")).isEqualTo(SubwayLineColors.byLineName("부산2호선"));
        assertThat(SubwayLineColors.byLineName("인천 1호선")).contains("#B4C7E7");
        assertThat(SubwayLineColors.byLineName("[일반]ITX청춘(천마산.신내)")).isEmpty();
        assertThat(SubwayLineColors.byLineId("gtfs:1:RS_X")).isEmpty();
        assertThat(SubwayLineColors.of(null, null)).isNull();
        assertThat(SubwayLineColors.of("1002", "부산1호선")).isEqualTo("#00A23F");   // id 먼저
    }

    @Test
    @DisplayName("표 파일: 공식 출처 노선은 source 가 있고, 못 찾은 노선은 NONE_FOUND 로 표시된다")
    void tableProvenance() throws Exception {
        JsonNode root = mapper.readTree(getClass().getResourceAsStream(SubwayLineColors.RESOURCE));
        assertThat(root.path("lines")).hasSize(36).allSatisfy(line -> {
            String type = line.path("sourceType").asText();
            assertThat(type).as(line.path("name").asText()).isIn("CITY_STANDARD", "OPERATOR", "NONE_FOUND");
            if (!type.equals("NONE_FOUND")) {
                assertThat(line.path("source").asText()).startsWith("https://");
            }
        });
    }

    @Test
    @DisplayName("OTP 그래프 빌드 표(route-colors.json subway)가 서버 색표와 같은 값이다 — 재빌드 뒤 그래프 색이 어긋나지 않게")
    void otpBuildTableMatches() throws Exception {
        JsonNode otp = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(
                "tools/transit-routing-benchmark/config/otp-national/route-colors.json"))).path("subway");
        assertThat(OTP_SUBWAY_NAMES).allSatisfy(name ->
                assertThat("#" + otp.path(name).asText()).as(name).isEqualTo(SubwayLineColors.byLineName(name).orElseThrow()));
    }

    @Test
    @DisplayName("잘못된 hex 줄은 건너뛰고 나머지는 읽는다")
    void parseSkipsBadRows() throws Exception {
        JsonNode root = mapper.readTree("""
                {"lines":[{"name":"A선","hex":"#12345","aliases":[]},{"name":"B선","hex":"#abcdef","aliases":["비선"]}]}""");
        assertThat(SubwayLineColors.parse(root)).containsOnlyKeys("B선", "비선").containsValue("#ABCDEF");
    }

    @Test
    @DisplayName("응답 계약: 지하철 DTO 마다 lineColor 키. 역직렬화는 lineColor 를 무시한다(저장된 JSON 호환)")
    void responsesCarryLineColor() throws Exception {
        assertLineColor(new SubwayStationsAheadResponse.Transfer("1077", "신분당선"), "#B81B30");
        assertLineColor(new StationResult.Transfer("1002", "2호선"), "#00A23F");
        assertLineColor(new JourneyNavigationGeometry.TransferLine("1006", "6호선"), "#7C4932");
        assertLineColor(new SubwayStationArrivalsResponse("0222", "1002", List.of()), "#00A23F");
        assertLineColor(new NearbySubwayStationResult("BS1-119", "서면", "BS1", "부산1호선", 35.15, 129.05, 80,
                null, "TIMETABLE"), "#F06A00");
        assertLineColor(new RegionalDeparturesResponse("BS2-219", "서면", "BS2", "부산2호선", "TIMETABLE",
                "WEEKDAY", "WEEKDAY", List.of()), "#81BF48");

        TrackViews.Stop subway = new TrackViews.Stop("1:RS_1", "서면", 35.15, 129.05, RoutingMode.SUBWAY, 80,
                null, null, null, null, null, List.of("부산2호선"), null, null);
        assertThat(mapper.readTree(mapper.writeValueAsString(subway)).path("lineColor").asText()).isEqualTo("#81BF48");
        TrackViews.Stop bus = new TrackViews.Stop("1:BS_1", "정류장", 37.5, 127.0, RoutingMode.BUS, 80,
                null, null, null, null, null, List.of("2호선"), null, null);
        assertThat(mapper.readTree(mapper.writeValueAsString(bus)).path("lineColor").isNull()).isTrue();
    }

    private void assertLineColor(Object dto, String expected) throws Exception {
        String json = mapper.writeValueAsString(dto);
        assertThat(mapper.readTree(json).path("lineColor").asText()).as(dto.getClass().getSimpleName()).isEqualTo(expected);
        assertThat(mapper.readValue(json, dto.getClass())).isEqualTo(dto);
    }
}
