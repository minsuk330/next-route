package watoo.grd.nextroute.application.arrival.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import watoo.grd.nextroute.application.arrival.dto.SubwayArrivalResponse;
import watoo.grd.nextroute.application.subway.dto.*;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@SpringBootTest
class SubwayArrivalQueryServiceTest {

    @Autowired
    SubwayArrivalQueryService service;

    @MockBean
    SubwayRealtimeCachePort cachePort;

    private SubwayRealtimeTrain train(String lineId, String stationName, String direction,
                                      String trainNo, int secs) {
        return SubwayRealtimeTrain.builder()
                .trainNo(trainNo).lineId(lineId).direction(direction)
                .stationName(stationName).arrivalSeconds(secs)
                .destinationName("성수행").currentMessage(secs + "초 후 도착")
                .build();
    }

    private SubwayRealtimeSnapshot snapshot(SubwayRealtimeTrain... trains) {
        return SubwayRealtimeSnapshot.builder()
                .collectedAt("2026-04-27T14:00:00+09:00")
                .status(SubwayRealtimeStatus.ACTIVE)
                .trains(List.of(trains))
                .build();
    }

    @Test
    void 구분자없으면_빈리스트() {
        assertThat(service.getArrivals("잠실역2호선")).isEmpty();
    }

    @Test
    void 없는역은_빈리스트() {
        List<SubwayArrivalResponse> result = service.getArrivals("없는역_2호선");
        assertThat(result).isEmpty();
    }

    @Test
    void 스냅샷없으면_빈리스트() {
        given(cachePort.readSnapshot()).willReturn(Optional.empty());
        List<SubwayArrivalResponse> result = service.getArrivals("을지로3가역_2호선");
        assertThat(result).isEmpty();
    }

    @Test
    void 스냅샷에서_역_필터링_반환() {
        given(cachePort.readSnapshot()).willReturn(Optional.of(snapshot(
                train("1002", "을지로3가", "상행", "T1", 60),
                train("1002", "을지로3가", "하행", "T2", 120),
                train("1003", "을지로3가", "상행", "T3", 80)
        )));
        List<SubwayArrivalResponse> result = service.getArrivals("을지로3가역_2호선");
        assertThat(result).isNotEmpty();
        assertThat(result).allMatch(r -> "1002".equals(r.getLineId()));
    }
}
