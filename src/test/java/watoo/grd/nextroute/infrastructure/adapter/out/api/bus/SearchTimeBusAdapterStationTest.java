package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.arrival.dto.SeoulStationArrival;
import watoo.grd.nextroute.application.bus.port.out.BusApiBreakerPort;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.Outcome;
import watoo.grd.nextroute.common.config.ClockConfig;
import watoo.grd.nextroute.common.config.JdkHttpClientMetrics;

/** 서울 정류장 단위 도착(#577) {@code getStationByUid}: 한도 끝점 STATION, 기다리지 않는 슬롯, 실응답 픽스처. */
@ExtendWith(MockitoExtension.class)
class SearchTimeBusAdapterStationTest {

    static final Clock CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 11, 16, 1, 0).atZone(ClockConfig.KST).toInstant(), ClockConfig.KST);

    @Mock RestClient restClient;
    @Mock BusApiBreakerPort breaker;
    @Mock BusApiQuotaPort quota;

    TransferArrivalProperties props = new TransferArrivalProperties();
    MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        props.setExternalCallAcquireMs(500);
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private SearchTimeBusAdapter adapter(Semaphore semaphore) {
        return new SearchTimeBusAdapter(restClient, breaker, quota, semaphore, props, CLOCK,
                new JdkHttpClientMetrics(new SimpleMeterRegistry()), "bus-key",
                server.url("/").toString().replaceAll("/$", ""));
    }

    private static String fixture(String name) throws Exception {
        try (var in = SearchTimeBusAdapterStationTest.class.getResourceAsStream("/arrival/stop/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void 실응답을_노선별_원본_행으로_읽고_STATION_몫을_쓴다() throws Exception {
        when(breaker.getBlockedUntil(BusApiQuotaPort.Endpoint.STATION)).thenReturn(Optional.empty());
        when(quota.tryAcquireSearch(BusApiQuotaPort.Endpoint.STATION)).thenReturn(true);
        server.enqueue(new MockResponse().setBody(fixture("seoul-station-22167.xml")));

        var result = adapter(new Semaphore(1)).getStationArrivals("22167", 1500);

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.data()).hasSize(13);
        SeoulStationArrival first = result.data().get(0);
        assertThat(first.busRouteId()).isEqualTo("104000014");
        assertThat(first.routeName()).isEqualTo("040");
        assertThat(first.routeType()).isEqualTo(3);
        assertThat(first.stId()).isEqualTo("121000091");
        assertThat(first.staOrd()).isEqualTo(56);
        assertThat(first.sectOrd1()).isEqualTo(51);
        assertThat(first.arrivalMsg1()).isEqualTo("[막차] 11분후[5번째 전]");
        assertThat(first.vehId2()).isEqualTo("0");
        assertThat(first.congestion1()).isEqualTo(3);
        assertThat(first.adirection()).isEqualTo("용산공영차고지");

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getPath()).startsWith("/stationinfo/getStationByUid?serviceKey=bus-key&arsId=22167");
        verify(quota).tryAcquireSearch(BusApiQuotaPort.Endpoint.STATION);
        verify(quota, never()).tryAcquireSearch(BusApiQuotaPort.Endpoint.ARRIVAL);
    }

    @Test
    void STATION_차단기가_막혀_있으면_부르지_않고_몫도_쓰지_않는다() {
        when(breaker.getBlockedUntil(BusApiQuotaPort.Endpoint.STATION)).thenReturn(Optional.of(Instant.MAX));

        var result = adapter(new Semaphore(1)).getStationArrivals("22167", 1500);

        assertThat(result.outcome()).isEqualTo(Outcome.BLOCKED);
        verifyNoInteractions(quota);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void 슬롯이_없으면_기다리지_않고_바로_LIMITED() {
        when(breaker.getBlockedUntil(BusApiQuotaPort.Endpoint.STATION)).thenReturn(Optional.empty());

        long started = System.nanoTime();
        var result = adapter(new Semaphore(0)).getStationArrivals("22167", 1500);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(result.outcome()).isEqualTo(Outcome.LIMITED);
        // 검색은 슬롯을 500 ms 까지 기다리지만 정류장 단위는 기다리지 않는다(검색이 먼저).
        assertThat(elapsedMs).isLessThan(200);
        verifyNoInteractions(quota);
    }

    @Test
    void STATION_몫이_소진되면_LIMITED_이고_슬롯을_돌려준다() {
        when(breaker.getBlockedUntil(BusApiQuotaPort.Endpoint.STATION)).thenReturn(Optional.empty());
        when(quota.tryAcquireSearch(BusApiQuotaPort.Endpoint.STATION)).thenReturn(false);
        Semaphore slots = new Semaphore(1);

        var result = adapter(slots).getStationArrivals("22167", 1500);

        assertThat(result.outcome()).isEqualTo(Outcome.LIMITED);
        assertThat(slots.availablePermits()).isEqualTo(1);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void 한도_초과_응답은_STATION_끝점에만_기록한다() {
        when(breaker.getBlockedUntil(BusApiQuotaPort.Endpoint.STATION)).thenReturn(Optional.empty());
        when(quota.tryAcquireSearch(BusApiQuotaPort.Endpoint.STATION)).thenReturn(true);
        when(breaker.recordApiLimit(eq(BusApiQuotaPort.Endpoint.STATION), any()))
                .thenReturn(new BusApiBreakerPort.LimitDecision(BusApiQuotaPort.Endpoint.STATION, 3, true, Instant.MAX));
        server.enqueue(new MockResponse().setBody("<ServiceResult><msgHeader><headerCd>7</headerCd>"
                + "<headerMsg>LIMITED NUMBER OF SERVICE REQUESTS EXCEEDS ERROR.</headerMsg><itemCount>0</itemCount>"
                + "</msgHeader><msgBody/></ServiceResult>"));

        var result = adapter(new Semaphore(1)).getStationArrivals("22167", 1500);

        assertThat(result.outcome()).isEqualTo(Outcome.BLOCKED);
        verify(breaker).recordApiLimit(eq(BusApiQuotaPort.Endpoint.STATION), any());
        verify(breaker, never()).recordApiLimit(eq(BusApiQuotaPort.Endpoint.ARRIVAL), any());
    }

    @Test
    void 정류장_단위는_검색_슬롯을_최대_3개만_잡고_나머지는_검색에_남긴다() {
        Semaphore slots = new Semaphore(8);
        SearchTimeBusAdapter adapter = adapter(slots);
        adapter.stationLimiter.acquireUninterruptibly(SearchTimeBusAdapter.STATION_MAX_CONCURRENT);
        try {
            var result = adapter.getStationArrivals("22167", 1500);

            assertThat(result.outcome()).isEqualTo(Outcome.LIMITED);
            assertThat(slots.availablePermits()).as("검색 슬롯은 건드리지 않는다").isEqualTo(8);
            verifyNoInteractions(quota);
        } finally {
            adapter.stationLimiter.release(SearchTimeBusAdapter.STATION_MAX_CONCURRENT);
        }
        assertThat(adapter.stationLimiter.availablePermits()).isEqualTo(3);
    }
}
