package watoo.grd.nextroute.application.arrival.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.arrival.dto.BusArrivalResponse;
import watoo.grd.nextroute.domain.bus.entity.BusArrivalRaw;
import watoo.grd.nextroute.domain.bus.service.BusDataService;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class BusArrivalQueryServiceTest {

    @Mock BusDataService busDataService;
    @InjectMocks BusArrivalQueryService service;

    @Test
    void getArrivals_returnsResponses() {
        BusArrivalRaw raw = BusArrivalRaw.builder()
                .routeId("100100001")
                .arrivalMsg1("3분 후")
                .predictTime1(180)
                .congestionNum1(3)
                .arrivalMsg2("8분 후")
                .predictTime2(480)
                .congestionNum2(2)
                .collectedAt(LocalDateTime.now())
                .stopId("22000")
                .build();

        given(busDataService.findLatestArrivalsByStopId(eq("22000"), any())).willReturn(List.of(raw));

        List<BusArrivalResponse> result = service.getArrivals("22000");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRouteId()).isEqualTo("100100001");
        assertThat(result.get(0).getPredictTime1()).isEqualTo(180);
    }

    @Test
    void getArrivals_emptyWhenNoData() {
        given(busDataService.findLatestArrivalsByStopId(any(), any())).willReturn(List.of());

        List<BusArrivalResponse> result = service.getArrivals("00000");

        assertThat(result).isEmpty();
    }
}
