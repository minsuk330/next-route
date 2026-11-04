package watoo.grd.nextroute.application.arrival.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteStopStore;

import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@DisplayName("경기 도착 조회 순번")
class RegionalTableGyeonggiStopOrderTest {

    private static final String ROUTE = "232000071";

    private final RegionalBusRouteStopStore store = mock(RegionalBusRouteStopStore.class);
    private final RegionalTableGyeonggiStopOrder lookup = new RegionalTableGyeonggiStopOrder(store);

    @Test
    @DisplayName("노선에 한 번 나오는 정류장은 표의 순번")
    void singleOccurrence() {
        given(store.findRouteStops(RegionalBusProvider.GG, ROUTE))
                .willReturn(List.of(stop("232000810", 1), stop("113000422", 26)));

        assertThat(lookup.orderOf("113000422", ROUTE)).isEqualTo(OptionalInt.of(26));
    }

    @Test
    @DisplayName("같은 정류장이 두 번이면 고르지 않는다")
    void repeatedStopIsAmbiguous() {
        given(store.findRouteStops(RegionalBusProvider.GG, ROUTE))
                .willReturn(List.of(stop("113000422", 26), stop("113000422", 40)));

        assertThat(lookup.orderOf("113000422", ROUTE)).isEmpty();
    }

    @Test
    @DisplayName("표에 없는 노선이나 정류장은 빈 값")
    void unknown() {
        given(store.findRouteStops(RegionalBusProvider.GG, ROUTE)).willReturn(List.of(stop("232000810", 1)));
        given(store.findRouteStops(RegionalBusProvider.GG, "200000014")).willReturn(List.of());

        assertThat(lookup.orderOf("113000422", ROUTE)).isEmpty();
        assertThat(lookup.orderOf("232000810", "200000014")).isEmpty();
    }

    private static RegionalRouteStop stop(String id, int seq) {
        return new RegionalRouteStop(id, "정류장", seq, false, null, false, 37.5, 127.0);
    }
}
