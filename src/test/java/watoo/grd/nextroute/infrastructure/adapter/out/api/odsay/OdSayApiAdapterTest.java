package watoo.grd.nextroute.infrastructure.adapter.out.api.odsay;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.route.config.OdSayProperties;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.exception.OdSayApiException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OdSayApiAdapterTest {

    OdSayApiAdapter adapter;

    @BeforeEach
    void setUp() {
        OdSayProperties properties = new OdSayProperties();
        properties.setBaseUrl("https://api.odsay.com/v1/api");
        properties.setKey("test-key");

        adapter = new OdSayApiAdapter(new ObjectMapper(), properties,
                new RouteSearchMetrics(new SimpleMeterRegistry()), RestClient.builder());
    }

    /**
     * searchPath 는 요청별 timeout 을 걸기 위해 RestClient 를 호출마다 만든다. 따라서 실제 HTTP 로 검증한다.
     */
    @Test
    void TC_searchPath는_경로검색_한_번만_호출한다() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "result": {
                            "searchType": 0,
                            "busCount": 0,
                            "subwayCount": 1,
                            "path": [
                              {
                                "pathType": 1,
                                "info": {
                                  "totalTime": 25,
                                  "payment": 1400,
                                  "totalWalk": 300,
                                  "subwayTransitCount": 1,
                                  "mapObj": "3:2:322:329@17:2:534:572",
                                  "firstStartStation": "출발역",
                                  "lastEndStation": "도착역"
                                },
                                "subPath": []
                              }
                            ]
                          }
                        }
                        """));

        OdSayProperties properties = new OdSayProperties();
        properties.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        properties.setKey("test-key");
        OdSayApiAdapter httpAdapter = new OdSayApiAdapter(new ObjectMapper(), properties,
                new RouteSearchMetrics(new SimpleMeterRegistry()), RestClient.builder());

        RouteSearchResult result = httpAdapter.searchPath(126.9, 37.5, 127.0, 37.6, 1000);

        // 경로검색 단 1회. 형상 보강용 추가 호출은 없다(P7 에서 ODsay 폴리라인 스택 제거).
        assertThat(server.getRequestCount()).isEqualTo(1);
        String path = server.takeRequest().getPath();
        assertThat(path)
                .contains("/searchPubTransPathT")
                .contains("SX=126.9")
                .contains("EY=37.6");

        assertThat(result.paths()).hasSize(1);
        // mapObj 는 응답 계약이라 그대로 싣는다(소비자는 없다 — 폴리라인은 OTP 형상이 채운다).
        assertThat(result.paths().get(0).info().mapObj()).isEqualTo("3:2:322:329@17:2:534:572");
        // laneGraphics 는 항상 비어 있다.
        assertThat(result.paths().get(0).laneGraphics()).isEmpty();

        server.shutdown();
    }

    @Test
    void TC_시간예산이_없으면_호출하지_않고_TIMEOUT() {
        assertThatThrownBy(() -> adapter.searchPath(126.9, 37.5, 127.0, 37.6, 0))
                .isInstanceOf(OdSayApiException.class)
                .satisfies(e -> assertThat(((OdSayApiException) e).getKind())
                        .isEqualTo(OdSayApiException.Kind.TIMEOUT));
    }
}
