package watoo.grd.nextroute.infrastructure.adapter.out.api.gyeonggi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiStopArrival;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.Outcome;
import watoo.grd.nextroute.infrastructure.adapter.out.api.ApiCallBlocker;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RegionalBusShared;

/** 경기 정류장 단위 도착(#577) {@code getBusArrivalListv2}. 픽스처는 2026-11-16 실응답. */
class GyeonggiStopArrivalAdapterTest {

    private static final String BASE = "https://apis.data.go.kr/6410000/busarrivalservice/v2";
    private static final String KEY = "key%2Fenc";

    private MockRestServiceServer server;
    private ApiCallBlocker blocker;
    private GyeonggiStopArrivalAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        blocker = new ApiCallBlocker();
        adapter = new GyeonggiStopArrivalAdapter(builder.build(), new ObjectMapper(), blocker, BASE, KEY,
                RegionalBusShared.disabled());
    }

    private static String fixture(String name) throws Exception {
        try (var in = GyeonggiStopArrivalAdapterTest.class.getResourceAsStream("/arrival/stop/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void respond(String stationId, String body) {
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(BASE + "/getBusArrivalListv2")))
                .andExpect(queryParam("stationId", stationId))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void 실응답의_노선_전부를_읽고_빈_문자열은_null_숫자_이름은_글자로() throws Exception {
        respond("201000107", fixture("gg-station-201000107.json"));

        var result = adapter.getStationArrivals("201000107");

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.data()).hasSize(5);
        GyeonggiStopArrival noBus = result.data().get(0);
        assertThat(noBus.arrival().routeId()).isEqualTo("200000144");
        assertThat(noBus.routeName()).as("원본은 숫자 99").isEqualTo("99");
        assertThat(noBus.routeTypeCd()).isEqualTo(13);
        assertThat(noBus.routeDestName()).isEqualTo("쎈트럴써밋");
        assertThat(noBus.arrival().predictMin1()).as("원본 \"\"").isNull();
        assertThat(noBus.arrival().vehicleId1()).isNull();
        server.verify();
    }

    @Test
    void 서울과_같이_쓰는_정류장은_서울_노선도_서울_id_로_온다() throws Exception {
        respond("121000009", fixture("gg-station-121000009.json"));

        var result = adapter.getStationArrivals("121000009");

        assertThat(result.data()).hasSize(54);
        assertThat(result.data()).extracting(r -> r.arrival().routeId()).contains("100100070");
    }

    @Test
    void 결과_없음_4는_성공한_빈_결과() {
        respond("299999999", "{\"response\":{\"msgHeader\":{\"resultCode\":4,\"resultMessage\":\"결과가 존재하지 않습니다.\"}}}");

        var result = adapter.getStationArrivals("299999999");

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.data()).isEmpty();
    }

    @Test
    void 한도_초과는_정류장_단위만_막고_노선_단위는_막지_않는다() {
        respond("201000107", "{\"OpenAPI_ServiceResponse\":{\"cmmMsgHeader\":{\"errMsg\":\"SERVICE ERROR\","
                + "\"returnReasonCode\":\"22\"}}}");

        var result = adapter.getStationArrivals("201000107");

        assertThat(result.outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(blocker.isBlocked(GyeonggiStopArrivalAdapter.API_NAME)).isTrue();
        assertThat(blocker.isBlocked(GyeonggiBusArrivalAdapter.API_NAME)).isFalse();
        // 막힌 뒤에는 부르지 않는다
        assertThat(adapter.getStationArrivals("201000107").outcome()).isEqualTo(Outcome.BLOCKED);
        server.verify();
    }

    @Test
    void 동시_자리가_없으면_부르지_않고_LIMITED() {
        adapter.localPermits.drainPermits();

        assertThat(adapter.getStationArrivals("201000107").outcome()).isEqualTo(Outcome.LIMITED);
        server.verify();   // 기대한 호출 없음 = 호출하면 실패
    }
}
