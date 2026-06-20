package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.bus.port.out.BusApiBreakerPort;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.common.config.ClockConfig;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
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

    private SearchTimeBusAdapter adapter(Semaphore semaphore) {
        props.setExternalCallAcquireMs(50);
        return new SearchTimeBusAdapter(
                restClient, breaker, quota, semaphore, props, CLOCK,
                "bus-key", "http://ws.bus.go.kr/api/rest");
    }

    @Test
    void TC_breaker_차단중이면_호출_생략_quota_미사용() {
        when(breaker.getBlockedUntil()).thenReturn(Optional.of(Instant.MAX));
        SearchTimeBusAdapter adapter = adapter(new Semaphore(8));

        assertThat(adapter.getArrInfoByStop("1111")).isEmpty();
        verifyNoInteractions(quota, restClient);
    }

    @Test
    void TC_quota_소진시_호출_생략() {
        when(breaker.getBlockedUntil()).thenReturn(Optional.empty());
        when(quota.tryAcquireSearch(BusApiQuotaPort.Endpoint.ARRIVAL)).thenReturn(false);
        SearchTimeBusAdapter adapter = adapter(new Semaphore(8));

        assertThat(adapter.getArrInfoByStop("1111")).isEmpty();
        verifyNoInteractions(restClient);
    }

    @Test
    void TC_동시_슬롯_없으면_호출_생략_quota_미사용() {
        when(breaker.getBlockedUntil()).thenReturn(Optional.empty());
        SearchTimeBusAdapter adapter = adapter(new Semaphore(0)); // 슬롯 0 → acquire timeout

        assertThat(adapter.getBusPosByRtid("R1")).isEmpty();
        verifyNoInteractions(quota, restClient);
    }

    @Test
    void TC_생략시에도_세마포어_누수_없음() {
        when(breaker.getBlockedUntil()).thenReturn(Optional.empty());
        when(quota.tryAcquireSearch(BusApiQuotaPort.Endpoint.ARRIVAL)).thenReturn(false);
        Semaphore sem = new Semaphore(1);
        SearchTimeBusAdapter adapter = adapter(sem);

        adapter.getArrInfoByStop("1111");
        adapter.getArrInfoByStop("2222");

        // quota false로 호출 생략돼도 슬롯은 release됨
        assertThat(sem.availablePermits()).isEqualTo(1);
    }
}
