package watoo.grd.nextroute.infrastructure.adapter.out.api.tago;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.Outcome;
import watoo.grd.nextroute.infrastructure.adapter.out.api.ApiCallBlocker;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RegionalBusShared;

/** TAGO 정류장 단위 도착(#577) {@code getSttnAcctoArvlPrearngeInfoList}. */
class TagoStopArrivalAdapterTest {

    private static final String BASE = "https://apis.data.go.kr/1613000/ArvlInfoInqireService";
    private static final String KEY = "key%2Fenc";

    private MockRestServiceServer server;
    private ApiCallBlocker blocker;
    private TagoBusArrivalAdapter routeAdapter;
    private TagoStopArrivalAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        blocker = new ApiCallBlocker();
        RestClient client = builder.build();
        routeAdapter = new TagoBusArrivalAdapter(client, new ObjectMapper(), blocker, Clock.systemUTC(), BASE, KEY);
        adapter = new TagoStopArrivalAdapter(client, new ObjectMapper(), blocker, BASE, KEY,
                RegionalBusShared.disabled(), routeAdapter);
    }

    private static String fixture(String name) throws Exception {
        try (var in = TagoStopArrivalAdapterTest.class.getResourceAsStream("/arrival/stop/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void respond(String page, String body) {
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(BASE + "/getSttnAcctoArvlPrearngeInfoList")))
                .andExpect(queryParam("cityCode", "21"))
                .andExpect(queryParam("nodeId", "BSB199080301"))
                .andExpect(queryParam("pageNo", page))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String page(int total, int from, int count) {
        String items = IntStream.range(from, from + count)
                .mapToObj(i -> "{\"arrprevstationcnt\":" + (i % 9 + 1) + ",\"arrtime\":" + (60 + i * 10)
                        + ",\"nodeid\":\"BSB199080301\",\"routeid\":\"BSB52000" + (10 + i % 40) + "000\",\"routeno\":"
                        + (10 + i % 40) + ",\"routetp\":\"일반버스\",\"vehicletp\":\"저상버스\"}")
                .collect(Collectors.joining(","));
        return "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL SERVICE.\"},\"body\":{\"items\":"
                + "{\"item\":[" + items + "]},\"numOfRows\":100,\"pageNo\":1,\"totalCount\":" + total + "}}}";
    }

    @Test
    void 실응답의_버스를_한대씩_읽는다() throws Exception {
        respond("1", fixture("tago-stop-BSB199080301.json"));

        var result = adapter.getStopArrivals("21", "BSB199080301");

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.data()).hasSize(4);
        assertThat(result.data().get(0).routeTp()).isNotBlank();
        assertThat(result.data().get(0).bus().arrSeconds()).isNotNull();
        server.verify();
    }

    @Test
    void 버스가_100대를_넘으면_다음_쪽까지_읽는다() {
        respond("1", page(130, 0, 100));
        respond("2", page(130, 100, 30));

        var result = adapter.getStopArrivals("21", "BSB199080301");

        assertThat(result.data()).hasSize(130);
        server.verify();
    }

    @Test
    void 버스가_없으면_성공한_빈_결과() {
        respond("1", "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL SERVICE.\"},"
                + "\"body\":{\"items\":\"\",\"numOfRows\":100,\"pageNo\":1,\"totalCount\":0}}}");

        var result = adapter.getStopArrivals("21", "BSB199080301");

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.data()).isEmpty();
    }

    @Test
    void 한도_초과는_정류장_단위만_막는다() {
        respond("1", "{\"OpenAPI_ServiceResponse\":{\"cmmMsgHeader\":{\"errMsg\":\"SERVICE ERROR\",\"returnReasonCode\":\"22\"}}}");

        assertThat(adapter.getStopArrivals("21", "BSB199080301").outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(blocker.isBlocked(TagoStopArrivalAdapter.API_NAME)).isTrue();
        assertThat(blocker.isBlocked(TagoBusArrivalAdapter.API_NAME)).isFalse();
    }

    @Test
    void 노선_단위와_같은_동시_자리를_나눠_쓴다() {
        routeAdapter.permits.drainPermits();

        assertThat(adapter.getStopArrivals("21", "BSB199080301").outcome()).isEqualTo(Outcome.LIMITED);
        server.verify();
    }

    @Test
    void 다음_쪽이_실패하면_앞_쪽만_성공으로_주지_않는다() {
        respond("1", page(130, 0, 100));
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(BASE + "/getSttnAcctoArvlPrearngeInfoList")))
                .andExpect(queryParam("pageNo", "2"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());

        var result = adapter.getStopArrivals("21", "BSB199080301");

        assertThat(result.outcome()).as("일부만 캐시되지 않게 실패").isEqualTo(Outcome.ERROR);
        server.verify();
    }

    @Test
    void 공유_모드에선_자리를_잡은_뒤_쪽마다_일일_몫을_세고_소진되면_부르지_않는다() {
        var props = new watoo.grd.nextroute.application.arrival.config.RegionalBusSharedProperties();
        props.setEnabled(true);
        var quota = org.mockito.Mockito.mock(watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort.class);
        var semaphore = org.mockito.Mockito.mock(watoo.grd.nextroute.infrastructure.adapter.out.cache.RedisNamedSemaphore.class);
        org.mockito.Mockito.when(semaphore.tryAcquire(org.mockito.ArgumentMatchers.eq("tago-bus"),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Optional.of("t1"));
        org.mockito.Mockito.when(quota.tryAcquire(TagoStopArrivalAdapter.QUOTA_BUCKET, 8000)).thenReturn(true, false);
        var shared = new RegionalBusShared(props, null, null, quota, semaphore, null);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer local = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        var sharedAdapter = new TagoStopArrivalAdapter(client, new ObjectMapper(), blocker, BASE, KEY, shared, routeAdapter);
        local.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(BASE + "/getSttnAcctoArvlPrearngeInfoList")))
                .andExpect(queryParam("pageNo", "1"))
                .andRespond(withSuccess(page(130, 0, 100), MediaType.APPLICATION_JSON));

        var result = sharedAdapter.getStopArrivals("21", "BSB199080301");

        assertThat(result.outcome()).as("둘째 쪽 몫이 없으면 일부만 주지 않는다").isEqualTo(Outcome.LIMITED);
        org.mockito.Mockito.verify(quota, org.mockito.Mockito.times(2)).tryAcquire(TagoStopArrivalAdapter.QUOTA_BUCKET, 8000);
        org.mockito.Mockito.verify(semaphore).release("tago-bus", "t1");
        local.verify();
    }
}
