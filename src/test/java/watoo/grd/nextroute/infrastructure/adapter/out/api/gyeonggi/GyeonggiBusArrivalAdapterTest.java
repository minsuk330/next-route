package watoo.grd.nextroute.infrastructure.adapter.out.api.gyeonggi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.arrival.port.out.GyeonggiStopOrderPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.Outcome;
import watoo.grd.nextroute.infrastructure.adapter.out.api.ApiCallBlocker;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 경기 도착 어댑터 계약. 응답 본문은 2026-10-05 실측 원문(잠실역.잠실대교남단 123000001 / 5600 228000184)을 줄였다.
 */
class GyeonggiBusArrivalAdapterTest {

    private static final String BASE = "https://apis.data.go.kr/6410000/busarrivalservice/v2";
    private static final String KEY = "TEST%2FKEY%3D%3D";

    /** 값이 있는 경우: 숫자로 온다. */
    private static final String ITEM_NUMERIC = """
            {"response":{"comMsgHeader":"","msgHeader":{"queryTime":"2026-10-05 16:30:25.865","resultCode":0,
            "resultMessage":"정상적으로 처리되었습니다."},"msgBody":{"busArrivalItem":{"crowded1":1,"crowded2":1,
            "flag":"PASS","locationNo1":1,"locationNo2":19,"predictTime1":1,"predictTime2":29,
            "remainSeatCnt1":33,"remainSeatCnt2":39,"routeId":228000184,"routeName":5600,"staOrder":62,
            "stationId":123000001}}}}
            """;

    /** 값이 없는 경우: "" 로 온다(기점 228000191 실측). */
    private static final String ITEM_BLANK = """
            {"response":{"comMsgHeader":"","msgHeader":{"resultCode":0,"resultMessage":"정상적으로 처리되었습니다."},
            "msgBody":{"busArrivalItem":{"crowded1":"","crowded2":"","flag":"PASS","locationNo1":"","locationNo2":"",
            "predictTime1":"","predictTime2":"","remainSeatCnt1":"","remainSeatCnt2":"","routeId":228000184,
            "routeName":5600,"staOrder":1,"stationId":228000191}}}}
            """;

    private static final String NO_RESULT = """
            {"response":{"comMsgHeader":"","msgHeader":{"resultCode":4,"resultMessage":"결과가 존재하지 않습니다."}}}
            """;

    private static String portalError(String reasonCode) {
        return """
                {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"ERR","returnAuthMsg":"ERR",
                "returnReasonCode":"%s"}}}
                """.formatted(reasonCode);
    }

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-05T07:30:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private final AtomicReference<GyeonggiStopOrderPort> stopOrder =
            new AtomicReference<>((stationId, routeId) -> OptionalInt.of(7));

    private MockRestServiceServer server;
    private ApiCallBlocker blocker;
    private GyeonggiBusArrivalAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        blocker = new ApiCallBlocker();
        adapter = new GyeonggiBusArrivalAdapter(builder.build(), new ObjectMapper(), blocker, clock, BASE, KEY,
                (stationId, routeId) -> stopOrder.get().orderOf(stationId, routeId));
    }

    private void expectCall(String stationId, String routeId, String body) {
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(BASE + "/getBusArrivalItemv2")))
                .andExpect(queryParam("stationId", stationId))
                .andExpect(queryParam("routeId", routeId))
                .andExpect(queryParam("staOrder", "7"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("순번이 하나로 안 정해지면 공급자를 부르지 않고 ERROR")
    void noUniqueOrderSkipsTheCall() {
        stopOrder.set((stationId, routeId) -> OptionalInt.empty());

        assertThat(adapter.getArrival("123000001", "228000184").outcome()).isEqualTo(Outcome.ERROR);

        server.verify();   // 기대한 호출 없음 = 호출하면 실패
    }

    @Test
    @DisplayName("순번 표를 못 읽으면 공급자를 부르지 않고 ERROR")
    void orderLookupFailureSkipsTheCall() {
        stopOrder.set((stationId, routeId) -> {
            throw new IllegalStateException("db down");
        });

        assertThat(adapter.getArrival("123000001", "228000184").outcome()).isEqualTo(Outcome.ERROR);

        server.verify();
    }

    @Test
    @DisplayName("공급자의 필수 값 거절(resultCode 2)은 ERROR 이고 캐시하지 않는다")
    void missingParameterRejectionIsNotCached() {
        String rejected = """
                {"response":{"msgHeader":{"resultCode":2,"resultMessage":"필수 요청 Parameter 가 존재하지 않습니다."}}}
                """;
        expectCall("113000422", "232000071", rejected);
        expectCall("113000422", "232000071", ITEM_NUMERIC);

        assertThat(adapter.getArrival("113000422", "232000071").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(adapter.getArrival("113000422", "232000071").outcome()).isEqualTo(Outcome.OK);
        server.verify();
    }

    @Test
    @DisplayName("숫자 응답을 그대로 읽는다(분·정류장 수·혼잡도·좌석·flag)")
    void parsesNumericItem() {
        expectCall("123000001", "228000184", ITEM_NUMERIC);

        BusQueryResult<GyeonggiBusArrival> r = adapter.getArrival("123000001", "228000184");

        assertThat(r.outcome()).isEqualTo(Outcome.OK);
        assertThat(r.data()).containsExactly(new GyeonggiBusArrival(
                "228000184", 1, 1, 1, 33, 29, 19, 1, 39, "PASS"));
        server.verify();
    }

    @Test
    @DisplayName("빈 문자열 값은 null 로 읽고 조회 자체는 성공(OK)")
    void blankValuesBecomeNull() {
        expectCall("228000191", "228000184", ITEM_BLANK);

        BusQueryResult<GyeonggiBusArrival> r = adapter.getArrival("228000191", "228000184");

        assertThat(r.isOk()).isTrue();
        assertThat(r.data()).containsExactly(new GyeonggiBusArrival(
                "228000184", null, null, null, null, null, null, null, null, "PASS"));
    }

    @Test
    @DisplayName("resultCode 4(결과 없음) → OK 빈 리스트 — '조회 못 함'과 구분")
    void noResultIsOkEmpty() {
        expectCall("123000001", "100100209", NO_RESULT);

        BusQueryResult<GyeonggiBusArrival> r = adapter.getArrival("123000001", "100100209");

        assertThat(r.outcome()).isEqualTo(Outcome.OK);
        assertThat(r.data()).isEmpty();
    }

    @Test
    @DisplayName("포털 한도 초과(22, HTTP 403) → BLOCKED 이고 그날 이후 호출하지 않는다")
    void quotaExceededBlocksForTheDay() {
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body(portalError("22")));

        assertThat(adapter.getArrival("123000001", "228000184").outcome()).isEqualTo(Outcome.BLOCKED);
        // 두 번째 호출은 HTTP 없이 BLOCKED(once() 기대라 호출되면 verify 실패)
        assertThat(adapter.getArrival("123000002", "228000184").outcome()).isEqualTo(Outcome.BLOCKED);
        server.verify();
    }

    @Test
    @DisplayName("차단 대상이 아닌 포털 오류(1 APPLICATION_ERROR) → ERROR, 차단 안 함")
    void transientPortalErrorDoesNotBlock() {
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                        .body(portalError("1")));

        assertThat(adapter.getArrival("123000001", "228000184").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(blocker.isBlocked(GyeonggiBusArrivalAdapter.API_NAME)).isFalse();
    }

    @Test
    @DisplayName("JSON 이 아닌 응답 → ERROR")
    void nonJsonIsError() {
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withSuccess("<html>gateway</html>", MediaType.TEXT_HTML));

        assertThat(adapter.getArrival("123000001", "228000184").outcome()).isEqualTo(Outcome.ERROR);
    }

    @Test
    @DisplayName("키 미설정 → HTTP 호출 없이 ERROR")
    void missingKeySkipsCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer noCalls = MockRestServiceServer.bindTo(builder).build();
        GyeonggiBusArrivalAdapter noKey = new GyeonggiBusArrivalAdapter(
                builder.build(), new ObjectMapper(), blocker, clock, BASE, "", (stationId, routeId) -> OptionalInt.of(7));

        assertThat(noKey.getArrival("123000001", "228000184").outcome()).isEqualTo(Outcome.ERROR);
        noCalls.verify();
    }

    @Test
    @DisplayName("15초 안 같은 (정류장, 노선) 재조회는 캐시(HTTP 1회), 15초 지나면 다시 호출")
    void cachesForFifteenSeconds() {
        expectCall("123000001", "228000184", ITEM_NUMERIC);

        assertThat(adapter.getArrival("123000001", "228000184").cacheHit()).isFalse();
        now.set(now.get().plusSeconds(14));
        BusQueryResult<GyeonggiBusArrival> cached = adapter.getArrival("123000001", "228000184");
        assertThat(cached.cacheHit()).isTrue();
        assertThat(cached.data()).hasSize(1);
        server.verify();

        server.reset();
        expectCall("123000001", "228000184", ITEM_NUMERIC);
        now.set(now.get().plusSeconds(2));
        assertThat(adapter.getArrival("123000001", "228000184").cacheHit()).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("실패는 캐시하지 않는다 — 다음 호출이 다시 provider 로 간다")
    void failuresAreNotCached() {
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withSuccess("not json", MediaType.TEXT_PLAIN));
        expectCall("123000001", "228000184", ITEM_NUMERIC);

        assertThat(adapter.getArrival("123000001", "228000184").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(adapter.getArrival("123000001", "228000184").isOk()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("Encoding 키를 다시 인코딩하지 않고 그대로 보낸다")
    void keyIsSentVerbatim() {
        server.expect(once(), requestTo(org.hamcrest.Matchers.containsString("serviceKey=" + KEY + "&")))
                .andRespond(withSuccess(NO_RESULT, MediaType.APPLICATION_JSON));

        adapter.getArrival("123000001", "228000184");
        server.verify();
    }
}
