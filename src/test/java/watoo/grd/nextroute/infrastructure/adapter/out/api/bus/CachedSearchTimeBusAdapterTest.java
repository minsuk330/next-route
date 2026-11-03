package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import watoo.grd.nextroute.application.route.config.SearchTimeBusProperties;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheReadPort;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheWritePort;
import watoo.grd.nextroute.application.bus.port.out.BusRouteObservationHistoryPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CachedSearchTimeBusAdapterTest {

    @Mock SearchTimeBusAdapter delegate;
    @Mock BusRealtimeCacheReadPort cacheRead;
    @Mock BusRealtimeCacheWritePort cacheWrite;
    @Mock BusRouteObservationHistoryPort observationHistory;

    CachedSearchTimeBusAdapter cache;

    @BeforeEach
    void setUp() {
        cache = new CachedSearchTimeBusAdapter(delegate, cacheRead, cacheWrite,
                new RouteSearchMetrics(new SimpleMeterRegistry()), new SearchTimeBusProperties(),
                observationHistory);
    }

    private BusPositionInfo pos(String vehId) {
        return new BusPositionInfo(
                vehId, 60, 3, 500.0, 10000.0, "0", "s1",
                "20260606130001", "AB1234", 0, 120, "prevStop",
                null, null, "0", "0", 1000.0, "nextStop",
                2, "turnStop", 127.01, 37.51, "1"
        );
    }

    @Test
    void TC_캐시_hit이면_delegate_미호출_cacheHit_true() {
        given(cacheRead.getPositions("R1")).willReturn(Optional.of(List.of(pos("v1"))));

        BusQueryResult<BusPositionInfo> result = cache.getBusPosByRtid("R1");

        assertThat(result.isOk()).isTrue();
        assertThat(result.cacheHit()).isTrue();   // provider 미호출 — cap·quota 미소모
        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).vehicleId()).isEqualTo("v1");
        verify(delegate, never()).getBusPosByRtid(any());
        verify(cacheWrite, never()).putPositions(any(), any());
    }

    @Test
    void TC_캐시_miss이면_delegate_호출하고_저장_cacheHit_false() {
        given(cacheRead.getPositions("R1")).willReturn(Optional.empty());
        given(delegate.getBusPosByRtid("R1")).willReturn(BusQueryResult.ok(List.of(pos("v1"))));

        BusQueryResult<BusPositionInfo> result = cache.getBusPosByRtid("R1");

        assertThat(result.data()).hasSize(1);
        assertThat(result.cacheHit()).isFalse();
        verify(cacheWrite).putPositions(eq("R1"), eq(List.of(pos("v1"))));
    }

    @Test
    void TC_빈_결과는_캐시하지_않음() {
        given(cacheRead.getPositions("R1")).willReturn(Optional.empty());
        given(delegate.getBusPosByRtid("R1")).willReturn(BusQueryResult.ok(List.of()));

        BusQueryResult<BusPositionInfo> result = cache.getBusPosByRtid("R1");

        assertThat(result.data()).isEmpty();
        verify(cacheWrite, never()).putPositions(any(), any());
    }

    @Test
    void TC_차단_결과는_캐시하지_않음() {
        given(cacheRead.getPositions("R1")).willReturn(Optional.empty());
        given(delegate.getBusPosByRtid("R1")).willReturn(BusQueryResult.blocked());

        BusQueryResult<BusPositionInfo> result = cache.getBusPosByRtid("R1");

        // BLOCKED outcome 전파, 캐시 안 함(고착 방지)
        assertThat(result.isOk()).isFalse();
        verify(cacheWrite, never()).putPositions(any(), any());
    }

    @Test
    void TC_도착조회_miss이면_delegate_호출하고_저장() {
        given(cacheRead.getArrivals("22000", "R1", 3)).willReturn(Optional.empty());
        given(delegate.getArrInfoByStop("22000", "R1", 3)).willReturn(BusQueryResult.ok(List.of()));

        cache.getArrInfoByStop("22000", "R1", 3);

        // 빈 결과라 저장 안 함
        verify(cacheWrite, never()).putArrivals(any(), any(), anyInt(), any());
    }

    @Test
    void TC_miss로_받은_새_관측만_ML_노선이력에_덧붙인다() {
        given(cacheRead.getPositions("R1")).willReturn(Optional.empty());
        given(delegate.getBusPosByRtid("R1")).willReturn(BusQueryResult.ok(List.of(pos("v1"))));

        cache.getBusPosByRtid("R1");

        org.mockito.Mockito.verify(observationHistory).append("R1", List.of(pos("v1")));
    }

    @Test
    void TC_캐시_hit은_ML_노선이력에_다시_쌓지_않는다() {
        given(cacheRead.getPositions("R1")).willReturn(Optional.of(List.of(pos("v1"))));

        cache.getBusPosByRtid("R1");

        org.mockito.Mockito.verifyNoInteractions(observationHistory);
    }
}
