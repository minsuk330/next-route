package watoo.grd.nextroute.infrastructure.adapter.out.api.tmap;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.route.config.TmapProperties;
import watoo.grd.nextroute.application.route.dto.WalkSegment;
import watoo.grd.nextroute.application.route.exception.TmapApiException;
import watoo.grd.nextroute.application.route.port.out.TmapPedestrianPort.WalkSearchCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TmapPedestrianAdapterTest {

    private MockWebServer server;
    private TmapPedestrianAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        TmapProperties properties = new TmapProperties();
        properties.setAppKey("test-key");
        properties.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        properties.setTimeoutMs(1500);
        properties.setRetryOnFailure(false);

        RestClient restClient = RestClient.builder().build();
        adapter = new TmapPedestrianAdapter(restClient, properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void TC_정상_응답_파싱_polyline과_walkSteps_생성() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "type":"FeatureCollection",
                          "features":[
                            {
                              "type":"Feature",
                              "geometry":{"type":"Point","coordinates":[127.027,37.497]},
                              "properties":{
                                "totalDistance":320,"totalTime":240,"index":0,
                                "pointType":"SP","turnType":200,
                                "description":"보행자도로 을 따라 99m 이동"
                              }
                            },
                            {
                              "type":"Feature",
                              "geometry":{"type":"LineString","coordinates":[[127.027,37.497],[127.028,37.498],[127.029,37.499]]},
                              "properties":{"index":1,"distance":99,"time":83,"name":"보행자도로"}
                            },
                            {
                              "type":"Feature",
                              "geometry":{"type":"Point","coordinates":[127.029,37.499]},
                              "properties":{"index":2,"pointType":"EP","turnType":201,"description":"도착"}
                            }
                          ]
                        }
                        """));

        WalkSegment result = adapter.search(new WalkSearchCommand(
                127.027, 37.497, 127.029, 37.499, "출발", "도착"));

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.totalDistance()).isEqualTo(320);
        assertThat(result.totalTime()).isEqualTo(240);
        // Point + LineString의 중복 좌표는 제거되어야 함
        assertThat(result.polyline()).hasSize(3);
        assertThat(result.polyline().get(0).x()).isEqualTo(127.027);
        assertThat(result.polyline().get(0).y()).isEqualTo(37.497);
        assertThat(result.polyline().get(2).x()).isEqualTo(127.029);
        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(0).pointType()).isEqualTo("SP");
        assertThat(result.steps().get(1).pointType()).isEqualTo("EP");
    }

    @Test
    void TC_빈_features_응답은_empty_세그먼트() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"type":"FeatureCollection","features":[]}
                        """));

        WalkSegment result = adapter.search(new WalkSearchCommand(
                999.0, 999.0, 999.1, 999.1, "X", "Y"));

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void TC_5xx_응답은_TmapApiException() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("server error"));

        assertThatThrownBy(() -> adapter.search(new WalkSearchCommand(
                127.0, 37.5, 127.1, 37.6, "출발", "도착")))
                .isInstanceOf(TmapApiException.class)
                .hasMessageContaining("5xx");
    }

    @Test
    void TC_요청_헤더와_경로_검증() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"type\":\"FeatureCollection\",\"features\":[]}"));

        adapter.search(new WalkSearchCommand(127.0, 37.5, 127.1, 37.6, "강남역 11번 출구", "도착"));

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).contains("/tmap/routes/pedestrian?version=1");
        assertThat(req.getHeader("appKey")).isEqualTo("test-key");
        assertThat(req.getHeader("Accept")).contains(MediaType.APPLICATION_JSON_VALUE);

        // 한글 startName이 JSON body에 그대로 들어가야 함 (URLEncode X)
        String body = req.getBody().readString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"startName\":\"강남역 11번 출구\"");
        assertThat(body).contains("\"endName\":\"도착\"");
        assertThat(body).contains("\"reqCoordType\":\"WGS84GEO\"");
    }

    @Test
    void TC_좌표_swap_lng_lat_순서_그대로_x_y_매핑() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "type":"FeatureCollection",
                          "features":[
                            {
                              "type":"Feature",
                              "geometry":{"type":"LineString","coordinates":[[127.0,37.5],[128.0,38.5]]},
                              "properties":{"index":0,"distance":10}
                            }
                          ]
                        }
                        """));

        WalkSegment result = adapter.search(new WalkSearchCommand(
                127.0, 37.5, 128.0, 38.5, "출발", "도착"));

        // GeoJSON [127.0, 37.5] → x=127.0 (lng), y=37.5 (lat)
        assertThat(result.polyline().get(0).x()).isEqualTo(127.0);
        assertThat(result.polyline().get(0).y()).isEqualTo(37.5);
        assertThat(result.polyline().get(1).x()).isEqualTo(128.0);
        assertThat(result.polyline().get(1).y()).isEqualTo(38.5);
    }
}
