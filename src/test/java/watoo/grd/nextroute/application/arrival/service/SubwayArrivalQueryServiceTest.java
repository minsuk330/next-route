package watoo.grd.nextroute.application.arrival.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.arrival.dto.SubwayArrivalResponse;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class SubwayArrivalQueryServiceTest {

    @Mock SubwayDataService subwayDataService;
    @InjectMocks SubwayArrivalQueryService service;

    @Test
    void getArrivals_deduplicatesByDirectionAndTrainNo() {
        LocalDateTime now = LocalDateTime.now();
        // same train collected twice — only newest survives
        SubwayArrivalRaw old = SubwayArrivalRaw.builder()
                .stationId("1002000233").lineId("1002").direction("상행")
                .trainNo("2001").arrivalSeconds(120).currentMessage("2정거장 전")
                .collectedAt(now.minusMinutes(3)).build();
        SubwayArrivalRaw recent = SubwayArrivalRaw.builder()
                .stationId("1002000233").lineId("1002").direction("상행")
                .trainNo("2001").arrivalSeconds(60).currentMessage("1정거장 전")
                .collectedAt(now.minusMinutes(1)).build();

        given(subwayDataService.findLatestArrivalsByStationId(eq("1002000233"), any()))
                .willReturn(List.of(old, recent));

        List<SubwayArrivalResponse> result = service.getArrivals("1002000233");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getArrivalSeconds()).isEqualTo(60);
    }

    @Test
    void getArrivals_sortsByArrivalSeconds() {
        LocalDateTime now = LocalDateTime.now();
        SubwayArrivalRaw far = SubwayArrivalRaw.builder()
                .stationId("1002000233").lineId("1002").direction("상행")
                .trainNo("2002").arrivalSeconds(300).collectedAt(now).build();
        SubwayArrivalRaw near = SubwayArrivalRaw.builder()
                .stationId("1002000233").lineId("1002").direction("상행")
                .trainNo("2001").arrivalSeconds(60).collectedAt(now).build();

        given(subwayDataService.findLatestArrivalsByStationId(eq("1002000233"), any()))
                .willReturn(List.of(far, near));

        List<SubwayArrivalResponse> result = service.getArrivals("1002000233");

        assertThat(result.get(0).getArrivalSeconds()).isEqualTo(60);
        assertThat(result.get(1).getArrivalSeconds()).isEqualTo(300);
    }
}
