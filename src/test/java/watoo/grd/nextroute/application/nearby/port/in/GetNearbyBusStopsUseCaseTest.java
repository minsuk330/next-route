package watoo.grd.nextroute.application.nearby.port.in;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.nearby.dto.NearbyBusStopResult;
import watoo.grd.nextroute.application.nearby.service.NearbyTransitService;
import watoo.grd.nextroute.domain.bus.repository.NearbyBusStopProjection;
import watoo.grd.nextroute.domain.bus.service.BusDataService;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class GetNearbyBusStopsUseCaseTest {

    @Mock BusDataService busDataService;
    @Mock SubwayDataService subwayDataService;
    @InjectMocks NearbyTransitService service;

    @Test
    @DisplayName("첫 번째 반경(500m)에서 결과가 있으면 projection을 DTO로 변환해 반환한다")
    void getNearbyBusStops_mapsProjectionToResult() {
        NearbyBusStopProjection projection = projection(
                "1001", "시청앞", "01-001", 37.5665, 126.9780, 134.6
        );

        given(busDataService.findNearbyStops(37.5665, 126.9780, 500.0, 5))
                .willReturn(List.of(projection));

        List<NearbyBusStopResult> result = service.getNearbyBusStops(37.5665, 126.9780, 5);

        assertThat(result).containsExactly(
                new NearbyBusStopResult("1001", "시청앞", "01-001", 37.5665, 126.9780, 135)
        );
    }

    @Test
    @DisplayName("limit이 0 이하이면 기본값 20을 사용한다")
    void getNearbyBusStops_usesDefaultLimitWhenInputIsZero() {
        // 반경 확장 루프 전체를 anyDouble()로 커버
        given(busDataService.findNearbyStops(eq(37.5665), eq(126.9780), anyDouble(), eq(20)))
                .willReturn(List.of());

        service.getNearbyBusStops(37.5665, 126.9780, 0);

        then(busDataService).should(atLeastOnce())
                .findNearbyStops(eq(37.5665), eq(126.9780), anyDouble(), eq(20));
    }

    @Test
    @DisplayName("limit이 최대값(50)을 넘으면 50으로 clamp 한다")
    void getNearbyBusStops_clampsLimitToMax() {
        given(busDataService.findNearbyStops(eq(37.5665), eq(126.9780), anyDouble(), eq(50)))
                .willReturn(List.of());

        service.getNearbyBusStops(37.5665, 126.9780, 999);

        then(busDataService).should(atLeastOnce())
                .findNearbyStops(eq(37.5665), eq(126.9780), anyDouble(), eq(50));
    }

    @Test
    @DisplayName("결과가 없으면 500m씩 확장해 최대 3000m까지 조회하고 빈 리스트를 반환한다")
    void getNearbyBusStops_expandsRadiusAndReturnsEmptyWhenNoResults() {
        given(busDataService.findNearbyStops(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .willReturn(List.of());

        List<NearbyBusStopResult> result = service.getNearbyBusStops(37.5665, 126.9780, 5);

        assertThat(result).isEmpty();
        // 500, 1000, 1500, 2000, 2500, 3000 총 6번 호출
        then(busDataService).should(org.mockito.Mockito.times(6))
                .findNearbyStops(anyDouble(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("중간 반경에서 결과를 찾으면 그 이후 반경은 조회하지 않는다")
    void getNearbyBusStops_stopsExpandingWhenResultsFound() {
        NearbyBusStopProjection projection = projection(
                "2001", "광화문", "02-001", 37.5720, 126.9769, 980.0
        );

        given(busDataService.findNearbyStops(37.5665, 126.9780, 500.0, 5)).willReturn(List.of());
        given(busDataService.findNearbyStops(37.5665, 126.9780, 1000.0, 5)).willReturn(List.of(projection));

        List<NearbyBusStopResult> result = service.getNearbyBusStops(37.5665, 126.9780, 5);

        assertThat(result).hasSize(1);
        // 1000m에서 찾았으므로 1500m 이후는 호출하지 않음
        then(busDataService).should(org.mockito.Mockito.times(2))
                .findNearbyStops(anyDouble(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("위도가 범위를 벗어나면 예외를 던지고 조회를 수행하지 않는다")
    void getNearbyBusStops_throwsWhenLatitudeIsOutOfRange() {
        assertThatThrownBy(() -> service.getNearbyBusStops(91.0, 126.9780, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("lat must be between -90 and 90");

        then(busDataService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("경도가 범위를 벗어나면 예외를 던지고 조회를 수행하지 않는다")
    void getNearbyBusStops_throwsWhenLongitudeIsOutOfRange() {
        assertThatThrownBy(() -> service.getNearbyBusStops(37.5665, 181.0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("lng must be between -180 and 180");

        then(busDataService).shouldHaveNoInteractions();
    }

    private NearbyBusStopProjection projection(
            String stopId, String stopName, String arsId,
            Double latitude, Double longitude, Double distMeters
    ) {
        return new NearbyBusStopProjection() {
            @Override public String getStopId() { return stopId; }
            @Override public String getStopName() { return stopName; }
            @Override public String getArsId() { return arsId; }
            @Override public Double getLatitude() { return latitude; }
            @Override public Double getLongitude() { return longitude; }
            @Override public Double getDistMeters() { return distMeters; }
        };
    }
}
