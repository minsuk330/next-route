package watoo.grd.nextroute.application.arrival.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.arrival.dto.BusArrivalResponse;
import watoo.grd.nextroute.application.bus.BusArrivalInfoFixtures;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.service.BusDataService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BusArrivalQueryServiceTest {

    static final String STOP = "22000";
    static final String ROUTE = "100100118";

    @Mock BusApiPort busApiPort;
    @Mock BusDataService busDataService;
    @InjectMocks BusArrivalQueryService service;

    @Test
    void getArrivals_singleMapping_queriesByOrdAndMaps() {
        given(busDataService.findBusRouteByStopAndRoute(STOP, ROUTE))
                .willReturn(List.of(routeStop(3)));
        given(busApiPort.getArrInfoByStop(STOP, ROUTE, "3"))
                .willReturn(List.of(BusArrivalInfoFixtures.arrivalInfo(
                        "v1", "12가1234", "3분 후", "v2", "12가5678", "8분 후")));

        List<BusArrivalResponse> result = service.getArrivals(STOP, ROUTE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRouteId()).isEqualTo(ROUTE);
        assertThat(result.get(0).getArrivalMsg1()).isEqualTo("3분 후");
    }

    @Test
    void getArrivals_noMapping_emptyAndNoApiCall() {
        given(busDataService.findBusRouteByStopAndRoute(STOP, ROUTE)).willReturn(List.of());

        List<BusArrivalResponse> result = service.getArrivals(STOP, ROUTE);

        assertThat(result).isEmpty();
        verify(busApiPort, never()).getArrInfoByStop(any(), any(), any());
    }

    @Test
    void getArrivals_ambiguousLoopMapping_emptyAndNoApiCall() {
        given(busDataService.findBusRouteByStopAndRoute(STOP, ROUTE))
                .willReturn(List.of(routeStop(3), routeStop(31)));

        List<BusArrivalResponse> result = service.getArrivals(STOP, ROUTE);

        assertThat(result).isEmpty();
        verify(busApiPort, never()).getArrInfoByStop(any(), any(), any());
    }

    private BusRouteStop routeStop(int seq) {
        return BusRouteStop.builder().routeId(ROUTE).stopId(STOP).seq(seq).build();
    }
}
