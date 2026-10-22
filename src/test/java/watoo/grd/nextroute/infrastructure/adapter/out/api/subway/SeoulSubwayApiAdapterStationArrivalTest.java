package watoo.grd.nextroute.infrastructure.adapter.out.api.subway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import watoo.grd.nextroute.infrastructure.adapter.out.api.ApiCallBlocker;

import java.net.URI;
import java.util.List;

import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 역별 도착 조회의 주소(#427).
 *
 * <p>역 이름을 인코딩하지 않으면 HTTP 클라이언트(Apache HttpClient 5)가 한글을 요청 줄에서 {@code ?} 로 바꾸고,
 * 서울 API 는 끝이 {@code /0/1000/} 인 요청을 <b>전 노선 ALL</b>(약 3,000행·2MB)로 답한다. 앱이 역 id 로 다시
 * 걸러 값은 맞으므로 응답 내용으로는 안 잡힌다 — 그래서 나가는 주소를 직접 고정한다.
 */
@DisplayName("지하철 역별 도착 조회 주소")
class SeoulSubwayApiAdapterStationArrivalTest {

    private static final String EMPTY = """
            {"errorMessage":{"status":200,"code":"INFO-000","message":"정상 처리되었습니다.","total":0},
             "realtimeArrivalList":[]}
            """;

    @Test
    @DisplayName("한글 역 이름은 퍼센트 인코딩되어 경로 마지막 칸에 들어간다")
    void stationNameIsPercentEncoded() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        SeoulSubwayApiAdapter adapter = new SeoulSubwayApiAdapter(restTemplate, new ApiCallBlocker(),
                "arrival-key", "position-key", "http://swopenapi.test/api/subway",
                "master-key", "route-key", "http://openapi.test");
        server.expect(once(), requestTo(URI.create(
                        "http://swopenapi.test/api/subway/arrival-key/json/realtimeStationArrival/0/1000/"
                                + "%EA%B0%95%EB%82%A8")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));

        adapter.getRealtimeArrivalByStation("강남");

        server.verify();
    }

    @Test
    @DisplayName("성수지선 성수행의 행선지 '성수지선'은 역 이름 '성수'로 읽는다 — 역 시트가 '성수지선 방면'으로 그리지 않게")
    void seongsuBranchDestinationIsTheStationName() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        SeoulSubwayApiAdapter adapter = new SeoulSubwayApiAdapter(restTemplate, new ApiCallBlocker(),
                "arrival-key", "position-key", "http://swopenapi.test/api/subway",
                "master-key", "route-key", "http://openapi.test");
        server.expect(once(), requestTo(containsString("/realtimeStationArrival/")))
                .andRespond(withSuccess(SEONGSU_BRANCH, MediaType.APPLICATION_JSON));

        List<SubwayArrivalInfo> rows = adapter.getRealtimeArrivalByStation("용답");

        assertThat(rows.get(0).destinationName()).isEqualTo("성수");
        assertThat(rows.get(1).destinationName()).as("다른 행선지는 원문 그대로").isEqualTo("신설동");
        server.verify();
    }

    /** 운영 용답 행 모양을 본뜬 두 행(2026-10-22 15:25 성수행 1618 외선이 bstatnNm "성수지선" 으로 옴). */
    private static final String SEONGSU_BRANCH = """
            {"errorMessage":{"status":200,"code":"INFO-000","message":"정상 처리되었습니다.","total":2},
             "realtimeArrivalList":[
              {"subwayId":"1002","updnLine":"외선","trainLineNm":"성수지선행 - 성수방면",
               "statnFid":"1002002112","statnTid":"1002000211","statnId":"1002002111","statnNm":"용답",
               "trnsitCo":"1","ordkey":"02001성수지선0","subwayList":"1002",
               "statnList":"1002002111","btrainSttus":"일반","barvlDt":"120","btrainNo":"1618",
               "bstatnId":"1002000211","bstatnNm":"성수지선","recptnDt":"2026-10-22 15:25:30",
               "arvlMsg2":"2전역 (용두)","arvlMsg3":"용두","arvlCd":"99","lstcarAt":"0"},
              {"subwayId":"1002","updnLine":"내선","trainLineNm":"신설동행 - 신답방면",
               "statnFid":"1002000211","statnTid":"1002002112","statnId":"1002002111","statnNm":"용답",
               "trnsitCo":"1","ordkey":"01001신설동0","subwayList":"1002",
               "statnList":"1002002111","btrainSttus":"일반","barvlDt":"300","btrainNo":"1619",
               "bstatnId":"1002002114","bstatnNm":"신설동","recptnDt":"2026-10-22 15:25:30",
               "arvlMsg2":"5분 후 (성수)","arvlMsg3":"성수","arvlCd":"99","lstcarAt":"0"}
             ]}
            """;

    /**
     * 2026-10-22 13:59 운영 원문 두 행. 9호선 둔촌오륜 하행(개화행 - 올림픽공원방면)은 {@code statnFid}=중앙보훈병원(지나온 역),
     * {@code statnTid}=올림픽공원(다음 역)으로 이름 그대로다. <b>공항철도는 둘이 바뀌어 온다</b> — 홍대입구 상행(인천공항2터미널행 -
     * 디지털미디어시티방면)의 {@code statnFid} 가 디지털미디어시티(다음 역), {@code statnTid} 가 공덕(지나온 역)이다.
     */
    private static final String AIRPORT_AND_LINE9 = """
            {"errorMessage":{"status":200,"code":"INFO-000","message":"정상 처리되었습니다.","total":2},
             "realtimeArrivalList":[
              {"subwayId":"1065","updnLine":"상행","trainLineNm":"인천공항2터미널행 - 디지털미디어시티방면",
               "statnFid":"1065006504","statnTid":"1065006502","statnId":"1065006503","statnNm":"홍대입구",
               "trnsitCo":"3","ordkey":"01002인천공항2터미널0","subwayList":"1002,1063,1065",
               "statnList":"1002000239,1063075314,1065006503","btrainSttus":"일반","barvlDt":"0","btrainNo":"A2121",
               "bstatnId":"1065006511","bstatnNm":"인천공항2터미널","recptnDt":"2026-10-22 13:59:32",
               "arvlMsg2":"[2]번째 전역 (서울)","arvlMsg3":"서울","arvlCd":"99","lstcarAt":"0"},
              {"subwayId":"1009","updnLine":"하행","trainLineNm":"개화행 - 올림픽공원방면",
               "statnFid":"1009000938","statnTid":"1009000936","statnId":"1009000937","statnNm":"둔촌오륜",
               "trnsitCo":"0","ordkey":"01000개화0","subwayList":"1009","statnList":"1009000931","btrainSttus":"일반",
               "barvlDt":"20","btrainNo":"9121","bstatnId":"1009000901","bstatnNm":"개화","recptnDt":"2026-10-22 13:59:59",
               "arvlMsg2":"둔촌오륜 도착","arvlMsg3":"둔촌오륜","arvlCd":"1","lstcarAt":"0"}]}
            """;

    @Test
    @DisplayName("직전·다음 역은 진행 방향 기준이다 — 공항철도는 statnFid/statnTid 가 바뀌어 와서 맞바꾼다")
    void prevAndNextFollowTheTrainDirection() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        SeoulSubwayApiAdapter adapter = new SeoulSubwayApiAdapter(restTemplate, new ApiCallBlocker(),
                "arrival-key", "position-key", "http://swopenapi.test/api/subway",
                "master-key", "route-key", "http://openapi.test");
        server.expect(once(), requestTo(containsString("/realtimeStationArrival/")))
                .andRespond(withSuccess(AIRPORT_AND_LINE9, MediaType.APPLICATION_JSON));

        List<SubwayArrivalInfo> rows = adapter.getRealtimeArrivalByStation("홍대입구");

        SubwayArrivalInfo airport = rows.get(0);
        assertThat(airport.prevStationId()).as("공덕 → 홍대입구 → 디지털미디어시티").isEqualTo("1065006502");
        assertThat(airport.nextStationId()).isEqualTo("1065006504");
        SubwayArrivalInfo line9 = rows.get(1);
        assertThat(line9.prevStationId()).as("다른 노선은 원문 그대로").isEqualTo("1009000938");
        assertThat(line9.nextStationId()).isEqualTo("1009000936");
        server.verify();
    }
}
