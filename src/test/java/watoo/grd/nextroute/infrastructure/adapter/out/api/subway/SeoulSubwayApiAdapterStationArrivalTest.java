package watoo.grd.nextroute.infrastructure.adapter.out.api.subway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import watoo.grd.nextroute.infrastructure.adapter.out.api.ApiCallBlocker;

import java.net.URI;

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
}
