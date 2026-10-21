package watoo.grd.nextroute.infrastructure.adapter.out.api.subway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayPositionFetchResult;
import watoo.grd.nextroute.application.subway.dto.SubwayPositionInfo;
import watoo.grd.nextroute.infrastructure.adapter.out.api.ApiCallBlocker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 위치 API 의 응답을 어떻게 읽는지.
 *
 * <p>서울 API 는 <b>봉투가 두 가지</b>다. 정상은 {@code {"errorMessage":{...}, "realtimePositionList":[...]}}
 * 이고, "데이터 없음"·한도 초과는 {@code errorMessage} 없이 <b>최상위</b>에 {@code status/code/message}
 * 를 둔다. 예전 코드는 앞의 모양만 읽어서 뒤의 것을 전부 {@code code=null} 인 오류로 보고 그 노선을
 * 자정까지 막았다.
 *
 * <p>운행이 끝난 노선(막차 뒤)과 아직 시작 안 한 노선(첫차 전, 수집은 04:30 부터)은 매일 "데이터
 * 없음"을 준다. 그래서 매일 전 노선이 막혀 수집기가 하루 종일 아무것도 못 받았다(2026-09-28·29 실측).
 */
class SeoulSubwayApiAdapterPositionTest {

    private static final String LINE_2 = "1002";

    /** 2026-09-29 VPS 에서 운영 키로 받은 원문. */
    private static final String NO_DATA = """
            {"status":500,"code":"INFO-200","message":"해당하는 데이터가 없습니다.","link":"","developerMessage":"","total":0}
            """;
    private static final String QUOTA_EXCEEDED = """
            {"status":500,"code":"ERROR-337","message":"일일 호출 한도를 초과했습니다.","link":"","developerMessage":"","total":0}
            """;
    private static final String ONE_TRAIN = """
            {"errorMessage":{"status":200,"code":"INFO-000","message":"정상 처리되었습니다.","total":1},
             "realtimePositionList":[{"subwayId":"1002","subwayNm":"2호선","statnId":"1002000210",
               "statnNm":"뚝섬","trainNo":"2300","lastRecptnDt":"20260929","recptnDt":"2026-09-29 17:40:00",
               "updnLine":"0","statnTid":"1002000201","statnTnm":"성수","trainSttus":"1",
               "directAt":"0","lstcarAt":"0"}]}
            """;

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private SeoulSubwayApiAdapter adapter;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        adapter = new SeoulSubwayApiAdapter(restTemplate, new ApiCallBlocker(),
                "arrival-key", "position-key", "http://swopenapi.test/api/subway",
                "master-key", "route-key", "http://openapi.test");
    }

    @Test
    @DisplayName("열차가 없다는 응답은 오류가 아니라 '정상, 열차 0대'다")
    void noTrainsIsCompleteAndEmpty() {
        respond(once(), NO_DATA);

        SubwayPositionFetchResult result = adapter.getRealtimePosition(LINE_2);

        assertThat(result.status()).isEqualTo(SubwayPositionFetchResult.Status.COMPLETE);
        assertThat(result.rows()).isEmpty();
        assertThat(result.errorCode()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("열차가 없다고 그 노선을 막지 않는다 — 첫차가 뜨면 바로 받는다")
    void noTrainsDoesNotBlockTheLine() {
        respond(times(2), NO_DATA);

        adapter.getRealtimePosition(LINE_2);
        SubwayPositionFetchResult second = adapter.getRealtimePosition(LINE_2);

        assertThat(second.errorCode()).as("두 번째도 실제로 물었다").isNotEqualTo("BLOCKED");
        server.verify();
    }

    @Test
    @DisplayName("최상위 봉투의 오류 코드를 읽는다 — 한도 초과가 code=null 로 숨지 않는다")
    void topLevelErrorCodeIsRead() {
        respond(once(), QUOTA_EXCEEDED);

        SubwayPositionFetchResult result = adapter.getRealtimePosition(LINE_2);

        assertThat(result.status()).isEqualTo(SubwayPositionFetchResult.Status.FAILED);
        assertThat(result.errorCode()).isEqualTo("ERROR-337");
        server.verify();
    }

    @Test
    @DisplayName("진짜 오류는 지금처럼 그 노선을 막는다")
    void realErrorStillBlocksTheLine() {
        respond(once(), QUOTA_EXCEEDED);

        adapter.getRealtimePosition(LINE_2);
        SubwayPositionFetchResult second = adapter.getRealtimePosition(LINE_2);

        assertThat(second.errorCode()).isEqualTo("BLOCKED");
        server.verify();
    }

    @Test
    @DisplayName("정상 응답은 그대로 읽는다")
    void normalResponseIsUnchanged() {
        respond(once(), ONE_TRAIN);

        SubwayPositionFetchResult result = adapter.getRealtimePosition(LINE_2);

        assertThat(result.status()).isEqualTo(SubwayPositionFetchResult.Status.COMPLETE);
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).trainNo()).isEqualTo("2300");
        server.verify();
    }

    /** 경춘선 ITX-청춘(2026-10-21 운영 실측 번호 2029). directAt 9 는 enum 이 UNKNOWN 으로 접는다. */
    private static final String ITX_TRAIN = """
            {"errorMessage":{"status":200,"code":"INFO-000","message":"정상 처리되었습니다.","total":1},
             "realtimePositionList":[{"subwayId":"1067","subwayNm":"경춘선","statnId":"1067080117",
               "statnNm":"회기","trainNo":"2029","lastRecptnDt":"20261021","recptnDt":"2026-10-21 20:21:00",
               "updnLine":"1","statnTid":"1067080140","statnTnm":"춘천","trainSttus":"2",
               "directAt":"9","lstcarAt":"0"}]}
            """;

    @Test
    @DisplayName("S3 directAt 원문을 enum 옆에 보존한다 — 9(ITX)는 enum 이 UNKNOWN 이라 원문만 남는다. 0 도 원문 그대로")
    void keepsRawDirectAt() {
        server.expect(once(), requestTo(containsString("/realtimePosition/")))
                .andRespond(withSuccess(ITX_TRAIN, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(containsString("/realtimePosition/")))
                .andRespond(withSuccess(ONE_TRAIN, MediaType.APPLICATION_JSON));

        SubwayPositionInfo itx = adapter.getRealtimePosition("1067").rows().get(0);
        SubwayPositionInfo normal = adapter.getRealtimePosition(LINE_2).rows().get(0);

        assertThat(itx.express()).isEqualTo(SubwayExpressType.UNKNOWN);
        assertThat(itx.directAtRaw()).isEqualTo("9");
        assertThat(normal.express()).isEqualTo(SubwayExpressType.NORMAL);
        assertThat(normal.directAtRaw()).isEqualTo("0");
        server.verify();
    }

    private void respond(org.springframework.test.web.client.ExpectedCount count, String body) {
        server.expect(count, requestTo(containsString("/realtimePosition/")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}
