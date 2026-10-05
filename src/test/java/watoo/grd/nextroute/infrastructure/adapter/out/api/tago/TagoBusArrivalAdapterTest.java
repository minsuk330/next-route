package watoo.grd.nextroute.infrastructure.adapter.out.api.tago;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.arrival.dto.TagoBusArrival;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.Outcome;
import watoo.grd.nextroute.infrastructure.adapter.out.api.ApiCallBlocker;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * TAGO 버스도착 어댑터 계약. 본문은 2026-10-05 실측 원문(부산 186 영도대교, 구미 171-1 구미역)을 줄였다.
 */
class TagoBusArrivalAdapterTest {

    private static final String BASE = "https://apis.data.go.kr/1613000/ArvlInfoInqireService";
    private static final String KEY = "TEST%2FKEY%3D%3D";

    /** 두 대 → item 배열. */
    private static final String TWO_BUSES = """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":[
            {"arrprevstationcnt":49,"arrtime":3643,"nodeid":"BSB167850202","nodenm":"영도대교.남포역",
             "routeid":"BSB5200186000","routeno":186,"routetp":"일반버스","vehicletp":"저상버스"},
            {"arrprevstationcnt":25,"arrtime":1721,"nodeid":"BSB167850202","nodenm":"영도대교.남포역",
             "routeid":"BSB5200186000","routeno":186,"routetp":"일반버스","vehicletp":"저상버스"}]},
            "numOfRows":10,"pageNo":1,"totalCount":2}}}
            """;

    /** 한 대 → item 이 배열이 아니라 객체. 하이픈 노선 id. */
    private static final String ONE_BUS = """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":
            {"arrprevstationcnt":21,"arrtime":1791,"nodeid":"GMB119","nodenm":"구미역 전(국민은행)",
             "routeid":"GMB171-110","routeno":"171-1","routetp":"일반버스","vehicletp":"일반차량"}},
            "numOfRows":10,"pageNo":1,"totalCount":1}}}
            """;

    private static final String NO_BUS = """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":"",
            "numOfRows":10,"pageNo":1,"totalCount":0}}}
            """;

    private static final String SESSION_LIMIT = """
            {"response":{"header":{"resultCode":99,"resultMsg":"가용한 세션이 존재하지 않습니다. (30/30)"},"body":""}}
            """;

    private static String portalError(String reasonCode) {
        return """
                {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"ERR","returnAuthMsg":"ERR",
                "returnReasonCode":"%s"}}}
                """.formatted(reasonCode);
    }

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-05T08:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private MockRestServiceServer server;
    private ApiCallBlocker blocker;
    private TagoBusArrivalAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        blocker = new ApiCallBlocker();
        adapter = new TagoBusArrivalAdapter(builder.build(), new ObjectMapper(), blocker, clock, BASE, KEY);
    }

    private void respond(String body) {
        server.expect(once(), requestTo(startsWith(BASE + "/getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("item 배열(두 대)을 그대로 읽는다 — 도시코드·정류장·노선을 쿼리로 보낸다")
    void parsesItemArray() {
        server.expect(once(), requestTo(startsWith(BASE)))
                .andExpect(queryParam("cityCode", "21"))
                .andExpect(queryParam("nodeId", "BSB167850202"))
                .andExpect(queryParam("routeId", "BSB5200186000"))
                .andRespond(withSuccess(TWO_BUSES, MediaType.APPLICATION_JSON));

        BusQueryResult<TagoBusArrival> r = adapter.getArrivals("21", "BSB167850202", "BSB5200186000");

        assertThat(r.outcome()).isEqualTo(Outcome.OK);
        assertThat(r.data()).containsExactly(
                new TagoBusArrival("BSB5200186000", 3643, 49, "저상버스"),
                new TagoBusArrival("BSB5200186000", 1721, 25, "저상버스"));
        server.verify();
    }

    @Test
    @DisplayName("한 대면 item 이 객체로 온다 — 그래도 한 건으로 읽는다(하이픈 노선 id 포함)")
    void parsesSingleItemObject() {
        respond(ONE_BUS);

        BusQueryResult<TagoBusArrival> r = adapter.getArrivals("37050", "GMB119", "GMB171-110");

        assertThat(r.data()).containsExactly(new TagoBusArrival("GMB171-110", 1791, 21, "일반차량"));
    }

    @Test
    @DisplayName("\"items\": \"\" → OK 빈 리스트(다가오는 버스 없음)")
    void emptyItemsIsOkEmpty() {
        respond(NO_BUS);

        BusQueryResult<TagoBusArrival> r = adapter.getArrivals("21", "BSB167850202", "BSB5200186000");

        assertThat(r.outcome()).isEqualTo(Outcome.OK);
        assertThat(r.data()).isEmpty();
    }

    @Test
    @DisplayName("resultCode 99(동시 세션 한도) → ERROR, 차단하지 않는다(일시적)")
    void sessionLimitIsTransientError() {
        respond(SESSION_LIMIT);

        assertThat(adapter.getArrivals("21", "BSB167850202", "BSB5200186000").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(blocker.isBlocked(TagoBusArrivalAdapter.API_NAME)).isFalse();
    }

    @Test
    @DisplayName("포털 한도 초과(22, HTTP 403) → BLOCKED 이고 이후 호출하지 않는다")
    void quotaExceededBlocks() {
        server.expect(once(), requestTo(startsWith(BASE)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body(portalError("22")));

        assertThat(adapter.getArrivals("21", "BSB167850202", "BSB5200186000").outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(adapter.getArrivals("22", "DGB7051001500", "DGB3000320000").outcome()).isEqualTo(Outcome.BLOCKED);
        server.verify();
    }

    @Test
    @DisplayName("키 미설정 → HTTP 호출 없이 ERROR")
    void missingKeySkipsCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer noCalls = MockRestServiceServer.bindTo(builder).build();
        TagoBusArrivalAdapter noKey = new TagoBusArrivalAdapter(
                builder.build(), new ObjectMapper(), blocker, clock, BASE, " ");

        assertThat(noKey.getArrivals("21", "BSB167850202", "BSB5200186000").outcome()).isEqualTo(Outcome.ERROR);
        noCalls.verify();
    }

    @Test
    @DisplayName("15초 안 같은 조회는 캐시(HTTP 1회), 지나면 다시 호출")
    void cachesForFifteenSeconds() {
        respond(TWO_BUSES);

        assertThat(adapter.getArrivals("21", "BSB167850202", "BSB5200186000").cacheHit()).isFalse();
        now.set(now.get().plusSeconds(14));
        assertThat(adapter.getArrivals("21", "BSB167850202", "BSB5200186000").cacheHit()).isTrue();
        server.verify();

        server.reset();
        respond(TWO_BUSES);
        now.set(now.get().plusSeconds(2));
        assertThat(adapter.getArrivals("21", "BSB167850202", "BSB5200186000").cacheHit()).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("실패는 캐시하지 않는다")
    void failuresAreNotCached() {
        respond(SESSION_LIMIT);
        respond(TWO_BUSES);

        assertThat(adapter.getArrivals("21", "BSB167850202", "BSB5200186000").isOk()).isFalse();
        assertThat(adapter.getArrivals("21", "BSB167850202", "BSB5200186000").isOk()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("동시 호출 자리가 모두 차 있으면 HTTP 없이 LIMITED")
    void concurrencyLimitGivesLimited() {
        adapter.permits.acquireUninterruptibly(TagoBusArrivalAdapter.MAX_CONCURRENT);
        try {
            assertThat(adapter.getArrivals("21", "BSB167850202", "BSB5200186000").outcome())
                    .isEqualTo(Outcome.LIMITED);
        } finally {
            adapter.permits.release(TagoBusArrivalAdapter.MAX_CONCURRENT);
        }
        server.verify();   // 기대 호출 없음
    }

    @Test
    @DisplayName("Encoding 키를 다시 인코딩하지 않는다")
    void keyIsSentVerbatim() {
        server.expect(once(), requestTo(containsString("serviceKey=" + KEY + "&")))
                .andRespond(withSuccess(NO_BUS, MediaType.APPLICATION_JSON));

        adapter.getArrivals("21", "BSB167850202", "BSB5200186000");
        server.verify();
    }
}
