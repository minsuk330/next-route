package watoo.grd.nextroute.infrastructure.adapter.out.api.naver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.place.config.NaverApiHubProperties;

class NaverApiHubLocalSearchAdapterTest {

    private MockWebServer server;
    private NaverApiHubLocalSearchAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        NaverApiHubProperties properties = new NaverApiHubProperties();
        properties.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        properties.setClientId("client-id");
        properties.setClientSecret("client-secret");
        adapter = new NaverApiHubLocalSearchAdapter(RestClient.builder().build(), properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void mapsApiHubCoordinatesAndRemovesSearchHighlightMarkup() throws InterruptedException {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"items":[{
                          "title":"<b>서울</b>특별<b>시청</b>",
                          "category":"공공,사회기관&gt;시청",
                          "address":"서울특별시 중구 태평로1가 31",
                          "roadAddress":"서울특별시 중구 세종대로 110",
                          "mapx":"1269783882",
                          "mapy":"375666103"
                        }]}
                        """));

        var places = adapter.search("서울시청", 5);

        assertThat(places).hasSize(1);
        assertThat(places.get(0).name()).isEqualTo("서울특별시청");
        assertThat(places.get(0).category()).isEqualTo("공공,사회기관>시청");
        assertThat(places.get(0).longitude()).isEqualTo(126.9783882);
        assertThat(places.get(0).latitude()).isEqualTo(37.5666103);
        assertThat(places.get(0).sources()).containsExactly("NAVER");

        var request = server.takeRequest();
        assertThat(request.getPath()).contains("/search/v1/local").contains("query=").contains("display=5");
        assertThat(request.getHeader("X-NCP-APIGW-API-KEY-ID")).isEqualTo("client-id");
        assertThat(request.getHeader("X-NCP-APIGW-API-KEY")).isEqualTo("client-secret");
    }
}
