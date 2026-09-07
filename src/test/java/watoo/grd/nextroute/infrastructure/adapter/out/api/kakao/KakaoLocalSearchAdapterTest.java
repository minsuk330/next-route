package watoo.grd.nextroute.infrastructure.adapter.out.api.kakao;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.place.dto.PlaceSearchResult;

class KakaoLocalSearchAdapterTest {

    private MockWebServer server;
    private KakaoLocalSearchAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        adapter = new KakaoLocalSearchAdapter(RestClient.builder().build(), "test-key", baseUrl);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void parsesDocumentsAndMapsCoordinatesRoadAddressAndAuthHeader() throws InterruptedException {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"documents":[{
                          "id":"12345",
                          "place_name":"스타벅스 강남역점",
                          "address_name":"서울 강남구 역삼동 800",
                          "road_address_name":"서울 강남구 강남대로 390",
                          "category_name":"음식점 > 카페 > 커피전문점 > 스타벅스",
                          "x":"127.0276",
                          "y":"37.4979"
                        }]}
                        """));

        var places = adapter.search("스타벅스", 15);

        assertThat(places).hasSize(1);
        PlaceSearchResult.Item item = places.get(0);
        assertThat(item.key()).isEqualTo("KAKAO:12345");
        assertThat(item.name()).isEqualTo("스타벅스 강남역점");
        assertThat(item.roadAddress()).isEqualTo("서울 강남구 강남대로 390");
        assertThat(item.address()).isEqualTo("서울 강남구 강남대로 390");
        assertThat(item.longitude()).isEqualTo(127.0276);
        assertThat(item.latitude()).isEqualTo(37.4979);
        assertThat(item.sources()).containsExactly("KAKAO");

        var request = server.takeRequest();
        assertThat(request.getPath()).contains("/v2/local/search/keyword.json")
                .contains("query=").contains("size=15");
        assertThat(request.getHeader("Authorization")).isEqualTo("KakaoAK test-key");
    }

    @Test
    void fallsBackToAddressNameWhenRoadAddressMissing() {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"documents":[{
                          "id":"7",
                          "place_name":"어린이대공원",
                          "address_name":"서울 광진구 능동 216",
                          "road_address_name":"",
                          "category_name":"여행 > 공원",
                          "x":"127.0810",
                          "y":"37.5480"
                        }]}
                        """));

        var places = adapter.search("어린이대공원", 15);

        assertThat(places).hasSize(1);
        assertThat(places.get(0).roadAddress()).isNull();
        assertThat(places.get(0).address()).isEqualTo("서울 광진구 능동 216");
    }
}
