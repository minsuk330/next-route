package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.bus.port.out.BusApiBreakerPort;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.common.config.ClockConfig;
import watoo.grd.nextroute.common.config.JdkHttpClientMetrics;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.*;

/**
 * 차단/제한 경로 단위 테스트. 정상 호출(RestClient 실호출)은 통합/수동 검증.
 */
@ExtendWith(MockitoExtension.class)
class SearchTimeBusAdapterTest {

    @Mock RestClient restClient;
    @Mock BusApiBreakerPort breaker;
    @Mock BusApiQuotaPort quota;

    static final Clock CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 6, 6, 13, 0).atZone(ClockConfig.KST).toInstant(), ClockConfig.KST);

    TransferArrivalProperties props = new TransferArrivalProperties();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private SearchTimeBusAdapter adapter(Semaphore semaphore) {
        props.setExternalCallAcquireMs(50);
        return new SearchTimeBusAdapter(
                restClient, breaker, quota, semaphore, props, CLOCK,
                new JdkHttpClientMetrics(registry), "bus-key", "http://ws.bus.go.kr/api/rest");
    }

    @Test
    void TC_breaker_차단중이면_BLOCKED_quota_미사용() {
        when(breaker.getBlockedUntil(BusApiQuotaPort.Endpoint.ARRIVAL)).thenReturn(Optional.of(Instant.MAX));
        SearchTimeBusAdapter adapter = adapter(new Semaphore(8));

        var result = adapter.getArrInfoByStop("1111", "R1", 3);
        assertThat(result.outcome()).isEqualTo(SearchTimeBusQueryPort.Outcome.BLOCKED);
        assertThat(result.data()).isEmpty();
        verifyNoInteractions(quota, restClient);
    }

    @Test
    void TC_quota_소진시_LIMITED() {
        when(breaker.getBlockedUntil(BusApiQuotaPort.Endpoint.ARRIVAL)).thenReturn(Optional.empty());
        when(quota.tryAcquireSearch(BusApiQuotaPort.Endpoint.ARRIVAL)).thenReturn(false);
        SearchTimeBusAdapter adapter = adapter(new Semaphore(8));

        var result = adapter.getArrInfoByStop("1111", "R1", 3);
        assertThat(result.outcome()).isEqualTo(SearchTimeBusQueryPort.Outcome.LIMITED);
        verifyNoInteractions(restClient);
    }

    @Test
    void TC_동시_슬롯_없으면_LIMITED_quota_미사용() {
        when(breaker.getBlockedUntil(BusApiQuotaPort.Endpoint.POSITION)).thenReturn(Optional.empty());
        SearchTimeBusAdapter adapter = adapter(new Semaphore(0)); // 슬롯 0 → acquire timeout

        var result = adapter.getBusPosByRtid("R1");
        assertThat(result.outcome()).isEqualTo(SearchTimeBusQueryPort.Outcome.LIMITED);
        verifyNoInteractions(quota, restClient);
    }

    @Test
    void TC_생략시에도_세마포어_누수_없음() {
        when(breaker.getBlockedUntil(BusApiQuotaPort.Endpoint.ARRIVAL)).thenReturn(Optional.empty());
        when(quota.tryAcquireSearch(BusApiQuotaPort.Endpoint.ARRIVAL)).thenReturn(false);
        Semaphore sem = new Semaphore(1);
        SearchTimeBusAdapter adapter = adapter(sem);

        adapter.getArrInfoByStop("1111", "R1", 3);
        adapter.getArrInfoByStop("2222", "R1", 3);

        // quota false로 호출 생략돼도 슬롯은 release됨
        assertThat(sem.availablePermits()).isEqualTo(1);
    }

    @Test
    void TC_요청별_잔여예산이_HTTP_대기시간을_제한() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            when(breaker.getBlockedUntil(BusApiQuotaPort.Endpoint.ARRIVAL)).thenReturn(Optional.empty());
            when(quota.tryAcquireSearch(BusApiQuotaPort.Endpoint.ARRIVAL)).thenReturn(true);
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

            SearchTimeBusAdapter adapter = new SearchTimeBusAdapter(
                    restClient, breaker, quota, new Semaphore(1), props, CLOCK,
                    new JdkHttpClientMetrics(registry), "bus-key", server.url("/").toString().replaceAll("/$", ""));

            long started = System.nanoTime();
            var result = adapter.getArrInfoByStop("1111", "R1", 3, 80);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(result.outcome()).isEqualTo(SearchTimeBusQueryPort.Outcome.ERROR);
            assertThat(elapsedMs).isLessThan(300);
            // 기한으로 끊은 호출도 지표에 남는다 — 평가기가 기다리는 바로 그 호출이다(#415).
            assertThat(registry.get("http.client.requests")
                    .tags("uri", "/arrive/getArrInfoByRoute", "status", "IO_ERROR",
                            "exception", "HttpTimeoutException")
                    .timer().count()).isEqualTo(1);
        } finally {
            server.shutdown();
        }
    }

    @Test
    void TC_노선단위_조회는_차단중이면_부르지않고_검색몫도_쓰지않는다() {
        when(breaker.getBlockedUntil(BusApiQuotaPort.Endpoint.ARRIVAL))
                .thenReturn(Optional.of(Instant.now().plusSeconds(60)));

        var result = adapter(new Semaphore(1)).getArrInfoByRouteAll("100100022", 1500);

        assertThat(result.outcome()).isEqualTo(SearchTimeBusQueryPort.Outcome.BLOCKED);
        verify(quota, never()).tryAcquireSearch(any());
    }

    @Test
    void TC_노선단위_조회는_검색몫을_쓰지않고_기한안에_끝난다() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            when(breaker.getBlockedUntil(BusApiQuotaPort.Endpoint.ARRIVAL)).thenReturn(Optional.empty());
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            SearchTimeBusAdapter adapter = new SearchTimeBusAdapter(
                    restClient, breaker, quota, new Semaphore(1), props, CLOCK,
                    new JdkHttpClientMetrics(registry), "bus-key", server.url("/").toString().replaceAll("/$", ""));

            long started = System.nanoTime();
            var result = adapter.getArrInfoByRouteAll("100100022", 80);

            assertThat(result.outcome()).isEqualTo(SearchTimeBusQueryPort.Outcome.ERROR);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(300);
            verify(quota, never()).tryAcquireSearch(any());
        } finally {
            server.shutdown();
        }
    }

    @Test
    void TC_401이면_남은_예산_안에서_대체키로_다시_부른다() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            when(breaker.getBlockedUntil(BusApiQuotaPort.Endpoint.POSITION)).thenReturn(Optional.empty());
            when(quota.tryAcquireSearch(BusApiQuotaPort.Endpoint.POSITION)).thenReturn(true);
            server.enqueue(new MockResponse().setResponseCode(401)
                    .setBody("{\"error\":\"Unauthorized\",\"status\":401}"));
            String ok = "<ServiceResult><msgHeader><headerCd>0</headerCd><headerMsg>ok</headerMsg>"
                    + "<itemCount>0</itemCount></msgHeader><msgBody></msgBody></ServiceResult>";
            server.enqueue(new MockResponse().setBody(ok));
            server.enqueue(new MockResponse().setBody(ok));

            SearchTimeBusAdapter adapter = new SearchTimeBusAdapter(
                    restClient, breaker, quota, new Semaphore(1), props, CLOCK,
                    new JdkHttpClientMetrics(registry), "bus-key", server.url("/").toString().replaceAll("/$", ""));
            adapter.setKeyFallback(new SeoulBusKeyFallback("bus-key", "old-key"));

            var first = adapter.getBusPosByRtid("R1", 2000);
            var second = adapter.getBusPosByRtid("R1", 2000);

            assertThat(first.outcome()).isEqualTo(SearchTimeBusQueryPort.Outcome.OK);
            assertThat(second.outcome()).isEqualTo(SearchTimeBusQueryPort.Outcome.OK);
            assertThat(server.takeRequest().getPath()).contains("serviceKey=bus-key");
            assertThat(server.takeRequest().getPath()).contains("serviceKey=old-key");
            assertThat(server.takeRequest().getPath()).contains("serviceKey=old-key");
            assertThat(server.getRequestCount()).isEqualTo(3);
        } finally {
            server.shutdown();
        }
    }
}
