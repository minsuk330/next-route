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

    @Test
    void addressSearchSkipsRegionMatchesAndUsesTheBuildingName() throws InterruptedException {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"documents":[
                          {"address_name":"서울 송파구","address_type":"REGION","x":"127.10","y":"37.50",
                           "address":{"address_name":"서울 송파구"},"road_address":null},
                          {"address_name":"서울 송파구 올림픽로 300","address_type":"ROAD_ADDR","x":"127.1025","y":"37.5125",
                           "address":{"address_name":"서울 송파구 신천동 29"},
                           "road_address":{"address_name":"서울 송파구 올림픽로 300","building_name":"롯데월드타워앤드롯데월드몰"}}
                        ]}
                        """));

        var found = adapter.searchAddress("서울 송파구 올림픽로 300");

        assertThat(found).isPresent();
        PlaceSearchResult.Item item = found.get();
        assertThat(item.key()).isEqualTo("KAKAO_ADDRESS:서울 송파구 올림픽로 300");
        assertThat(item.name()).isEqualTo("롯데월드타워앤드롯데월드몰");
        assertThat(item.address()).isEqualTo("서울 송파구 올림픽로 300");
        assertThat(item.roadAddress()).isEqualTo("서울 송파구 올림픽로 300");
        assertThat(item.latitude()).isEqualTo(37.5125);
        assertThat(item.longitude()).isEqualTo(127.1025);
        assertThat(item.category()).isEqualTo("주소");
        assertThat(item.sources()).containsExactly("KAKAO");

        var request = server.takeRequest();
        assertThat(request.getPath()).contains("/v2/local/search/address.json").contains("size=3");
        assertThat(request.getHeader("Authorization")).isEqualTo("KakaoAK test-key");
    }

    @Test
    void addressWithoutBuildingNameIsNamedByTheAddressAndJibunOnlyHasNoRoadAddress() {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"documents":[{"address_name":"서울 관악구 신림동 1422-5","address_type":"REGION_ADDR",
                          "x":"126.9297","y":"37.4842","address":{"address_name":"서울 관악구 신림동 1422-5"},"road_address":null}]}
                        """));

        var item = adapter.searchAddress("서울 관악구 신림동 1422-5").orElseThrow();

        assertThat(item.name()).isEqualTo("서울 관악구 신림동 1422-5");
        assertThat(item.address()).isEqualTo("서울 관악구 신림동 1422-5");
        assertThat(item.roadAddress()).isNull();
    }

    @Test
    void addressSearchReturnsEmptyWhenOnlyRegionMatches() {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"documents":[{"address_name":"서울 강남구 역삼동","address_type":"REGION","x":"127.03","y":"37.50",
                          "address":{"address_name":"서울 강남구 역삼동"},"road_address":null}]}
                        """));

        assertThat(adapter.searchAddress("역삼동 1")).isEmpty();
    }
}
