package watoo.grd.nextroute.infrastructure.adapter.out.api.regionalbus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop;
import watoo.grd.nextroute.application.regionalbus.dto.TagoCity;
import watoo.grd.nextroute.application.regionalbus.dto.TagoRoute;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusSourceException;

import java.net.SocketTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 경기·TAGO 노선 원천 어댑터 계약(#267). 본문은 2026-10-06 실측 원문(대전 1001, 경기 7770)을 줄였다.
 */
class RegionalBusRouteSourceAdapterTest {

    private static final String TAGO = "https://apis.data.go.kr/1613000/BusRouteInfoInqireService";
    private static final String GG = "https://apis.data.go.kr/6410000/busrouteservice/v2";
    private static final String KEY = "TEST%2FKEY%3D%3D";

    private MockRestServiceServer server;
    private RegionalBusRouteSourceAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new RegionalBusRouteSourceAdapter(builder.build(), new ObjectMapper(), TAGO, GG, KEY, KEY);
    }

    private void respond(String path, String body) {
        server.expect(requestTo(containsString(path)))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("I/O 오류(읽기 시간 초과)는 재시도 가능 — 한 번의 timeout 이 적재를 중단시키지 않는다")
    void ioErrorIsRetryable() {
        server.expect(requestTo(containsString("/getCtyCodeList")))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> adapter.tagoCities())
                .isInstanceOfSatisfying(RegionalBusSourceException.class, e -> {
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.blocking()).isFalse();
                });
    }

    @Test
    @DisplayName("TAGO 도시 목록: item 배열")
    void cities() {
        respond("/getCtyCodeList", """
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":[
                {"citycode":25,"cityname":"대전광역시"},{"citycode":32050,"cityname":"태백시"}]},
                "numOfRows":200,"pageNo":1,"totalCount":2}}}
                """);

        assertThat(adapter.tagoCities()).containsExactly(
                new TagoCity("25", "대전광역시"), new TagoCity("32050", "태백시"));
    }

    @Test
    @DisplayName("TAGO 노선 목록: 영문·하이픈 id 그대로, totalCount 로 다음 쪽까지 읽는다")
    void routesPaged() {
        String page1 = "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":["
                + "{\"routeid\":\"BSB5201002F00\",\"routeno\":\"1002\"}]},\"numOfRows\":1000,\"pageNo\":1,"
                + "\"totalCount\":1001}}}";
        String page2 = """
                {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":
                {"routeid":"GMB171-110","routeno":"171-1"}},"numOfRows":1000,"pageNo":2,"totalCount":1001}}}
                """;
        server.expect(requestTo(containsString("pageNo=1"))).andExpect(queryParam("cityCode", "21"))
                .andRespond(withSuccess(page1, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("pageNo=2")))
                .andRespond(withSuccess(page2, MediaType.APPLICATION_JSON));

        assertThat(adapter.tagoRoutes("21")).containsExactly(
                new TagoRoute("21", "BSB5201002F00", "1002"),
                new TagoRoute("21", "GMB171-110", "171-1"));
        server.verify();
    }

    @Test
    @DisplayName("TAGO 경유정류소: 한 건이면 item 이 객체, 가상점 판별은 하지 않는다(null)")
    void tagoStopsSingleObject() {
        respond("/getRouteAcctoThrghSttnList", """
                {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":
                {"gpslati":36.49309,"gpslong":127.327,"nodeid":"DJB9007519","nodenm":"집현동 기점지",
                 "nodeord":1,"routeid":"DJB30300151","updowncd":0}},"numOfRows":1000,"pageNo":1,"totalCount":1}}}
                """);

        assertThat(adapter.tagoRouteStops("25", "DJB30300151")).containsExactly(
                new RegionalRouteStop("DJB9007519", "집현동 기점지", 1, null, 0, null, 36.49309, 127.327));
    }

    @Test
    @DisplayName("TAGO: items 가 빈 문자열이면 0건")
    void tagoEmpty() {
        respond("/getRouteAcctoThrghSttnList", """
                {"response":{"header":{"resultCode":"00"},"body":{"items":"","numOfRows":1000,"pageNo":1,"totalCount":0}}}
                """);

        assertThat(adapter.tagoRouteStops("25", "DJB1")).isEmpty();
    }

    @Test
    @DisplayName("TAGO resultCode 99(동시 세션 한도)는 다시 시도할 오류")
    void tagoSessionLimitIsRetryable() {
        respond("/getRouteNoList", """
                {"response":{"header":{"resultCode":99,"resultMsg":"가용한 세션이 존재하지 않습니다. (30/30)"},"body":""}}
                """);

        assertThatThrownBy(() -> adapter.tagoRoutes("25"))
                .isInstanceOfSatisfying(RegionalBusSourceException.class, e -> {
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.blocking()).isFalse();
                });
    }

    @Test
    @DisplayName("포털 공통 오류 30(미등록 키)은 적재를 멈출 오류, 그 밖 코드는 그 노선만 실패")
    void portalErrors() {
        respond("/getRouteAcctoThrghSttnList", portalError("30"));
        respond("/getBusRouteStationListv2", portalError("99"));

        assertThatThrownBy(() -> adapter.tagoRouteStops("25", "DJB1"))
                .isInstanceOfSatisfying(RegionalBusSourceException.class, e -> assertThat(e.blocking()).isTrue());
        assertThatThrownBy(() -> adapter.gyeonggiRouteStops("233000031"))
                .isInstanceOfSatisfying(RegionalBusSourceException.class, e -> {
                    assertThat(e.blocking()).isFalse();
                    assertThat(e.retryable()).isFalse();
                });
    }

    @Test
    @DisplayName("경기 노선정류소: (경유) 이름은 가상점, turnYn=Y 는 회차, 좌표 x=경도 y=위도")
    void gyeonggiStops() {
        respond("/getBusRouteStationListv2", """
                {"response":{"comMsgHeader":"","msgHeader":{"resultCode":0,"resultMessage":"정상적으로 처리되었습니다."},
                "msgBody":{"busRouteStationList":[
                {"stationId":277103108,"stationName":"서둔동차고지대기(경유)","stationSeq":1,"turnYn":"N","x":126.97,"y":37.27},
                {"stationId":200000075,"stationName":"사당역(중)","stationSeq":33,"turnYn":"Y","x":126.98,"y":37.47}]}}}
                """);

        List<RegionalRouteStop> stops = adapter.gyeonggiRouteStops("233000031");

        assertThat(stops).containsExactly(
                new RegionalRouteStop("277103108", "서둔동차고지대기(경유)", 1, false, null, true, 37.27, 126.97),
                new RegionalRouteStop("200000075", "사당역(중)", 33, true, null, false, 37.47, 126.98));
    }

    @Test
    @DisplayName("경기 resultCode 4(결과 없음)는 0건, 그 밖 비정상 코드는 실패")
    void gyeonggiNoResult() {
        respond("/getBusRouteStationListv2", """
                {"response":{"msgHeader":{"resultCode":4,"resultMessage":"결과가 존재하지 않습니다."}}}
                """);
        respond("/getBusRouteStationListv2", """
                {"response":{"msgHeader":{"resultCode":1,"resultMessage":"시스템 에러"}}}
                """);

        assertThat(adapter.gyeonggiRouteStops("200000000")).isEmpty();
        assertThatThrownBy(() -> adapter.gyeonggiRouteStops("200000000"))
                .isInstanceOf(RegionalBusSourceException.class);
    }

    @Test
    @DisplayName("키가 없으면 호출하지 않고 적재를 멈출 오류")
    void missingKey() {
        RegionalBusRouteSourceAdapter noKey = new RegionalBusRouteSourceAdapter(
                RestClient.builder().build(), new ObjectMapper(), TAGO, GG, "", "");

        assertThatThrownBy(noKey::tagoCities)
                .isInstanceOfSatisfying(RegionalBusSourceException.class, e -> assertThat(e.blocking()).isTrue());
    }

    private static String portalError(String reasonCode) {
        return """
                {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"ERR","returnAuthMsg":"ERR",
                "returnReasonCode":"%s"}}}
                """.formatted(reasonCode);
    }
}
