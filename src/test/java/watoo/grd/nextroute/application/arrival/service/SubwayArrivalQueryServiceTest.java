package watoo.grd.nextroute.application.arrival.service;

import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;
import watoo.grd.nextroute.domain.subway.repository.NearbySubwayStationProjection;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SubwayArrivalQueryServiceTest {

    private final SubwayRealtimeCachePort cachePort = mock(SubwayRealtimeCachePort.class);
    private final SubwayDataService subwayDataService = mock(SubwayDataService.class);
    private final SubwayArrivalQueryService service =
            new SubwayArrivalQueryService(cachePort, subwayDataService);

    @Test
    void resolveStation_selectsTheRequestedInternalLine() {
        NearbySubwayStationProjection lineNine = station("923", "신논현", "1009");
        NearbySubwayStationProjection sinbundang = station("D07", "신논현", "1077");
        given(subwayDataService.findNearbyStations(37.5045, 127.0250, 50, 5))
                .willReturn(List.of(lineNine, sinbundang));

        assertThat(service.resolveStation(37.5045, 127.0250, "1077"))
                .contains(new SubwayArrivalQueryService.ResolvedStation(
                        "D07", "신논현", "1077"));
    }

    private NearbySubwayStationProjection station(String stationId, String name, String lineId) {
        NearbySubwayStationProjection station = mock(NearbySubwayStationProjection.class);
        given(station.getStationId()).willReturn(stationId);
        given(station.getStationName()).willReturn(name);
        given(station.getLineId()).willReturn(lineId);
        return station;
    }
}
