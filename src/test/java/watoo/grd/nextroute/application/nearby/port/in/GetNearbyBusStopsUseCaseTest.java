package watoo.grd.nextroute.application.nearby.port.in;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.nearby.dto.NearbyBusStopResult;
import watoo.grd.nextroute.application.nearby.service.NearbyTransitService;
import watoo.grd.nextroute.application.route.service.PredictionSupportService;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.bus.repository.NearbyBusStopProjection;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class GetNearbyBusStopsUseCaseTest {

    @Mock BusStopRepository busStopRepository;
    @Mock BusRouteStopRepository busRouteStopRepository;
    @Mock SubwayStationRepository subwayStationRepository;
    @Mock PredictionSupportService predictionSupportService;
    @Mock watoo.grd.nextroute.application.subway.service.SubwayGtfsStopIndex subwayGtfsStopIndex;
    @InjectMocks NearbyTransitService service;

    @Test
    @DisplayName("첫 번째 반경(500m)에서 결과가 있으면 projection을 DTO로 변환해 반환한다 (미지원 정류장은 false)")
    void getNearbyBusStops_mapsProjectionToResult() {
        NearbyBusStopProjection projection = projection(
                "1001", "시청앞", "01-001", 37.5665, 126.9780, 134.6
        );

        given(busStopRepository.findNearby(37.5665, 126.9780, 500.0, 5))
                .willReturn(List.of(projection));
        given(predictionSupportService.supportedRouteIds()).willReturn(Set.of());
        // routeIds가 비어 findSupportedStopIds는 호출되지 않는다(NearbyTransitService의 빈 컬렉션 가드).

        List<NearbyBusStopResult> result = service.getNearbyBusStops(37.5665, 126.9780, 5);

        assertThat(result).containsExactly(
                new NearbyBusStopResult("1001", "시청앞", "01-001", 37.5665, 126.9780, 135, false, null)
        );
    }

    @Test
    @DisplayName("지원 노선을 경유하는 정류장은 supportsPrediction=true")
    void getNearbyBusStops_marksSupportedStop() {
        NearbyBusStopProjection projection = projection(
                "1001", "시청앞", "01-001", 37.5665, 126.9780, 134.6
        );

        given(busStopRepository.findNearby(37.5665, 126.9780, 500.0, 5))
                .willReturn(List.of(projection));
        given(predictionSupportService.supportedRouteIds()).willReturn(Set.of("100100124"));
        given(busRouteStopRepository.findSupportedStopIds(anySet(), anySet())).willReturn(List.of("1001"));

        List<NearbyBusStopResult> result = service.getNearbyBusStops(37.5665, 126.9780, 5);

        assertThat(result).containsExactly(
                new NearbyBusStopResult("1001", "시청앞", "01-001", 37.5665, 126.9780, 135, true, null)
        );
    }

    @Test
    @DisplayName("limit이 0 이하이면 기본값 20을 사용한다")
    void getNearbyBusStops_usesDefaultLimitWhenInputIsZero() {
        // 반경 확장 루프 전체를 anyDouble()로 커버
        given(busStopRepository.findNearby(eq(37.5665), eq(126.9780), anyDouble(), eq(20)))
                .willReturn(List.of());

        service.getNearbyBusStops(37.5665, 126.9780, 0);

        then(busStopRepository).should(atLeastOnce())
                .findNearby(eq(37.5665), eq(126.9780), anyDouble(), eq(20));
    }

    @Test
    @DisplayName("limit이 최대값(50)을 넘으면 50으로 clamp 한다")
    void getNearbyBusStops_clampsLimitToMax() {
        given(busStopRepository.findNearby(eq(37.5665), eq(126.9780), anyDouble(), eq(50)))
                .willReturn(List.of());

        service.getNearbyBusStops(37.5665, 126.9780, 999);

        then(busStopRepository).should(atLeastOnce())
                .findNearby(eq(37.5665), eq(126.9780), anyDouble(), eq(50));
    }

    @Test
    @DisplayName("결과가 없으면 500m씩 확장해 최대 3000m까지 조회하고 빈 리스트를 반환한다")
    void getNearbyBusStops_expandsRadiusAndReturnsEmptyWhenNoResults() {
        given(busStopRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .willReturn(List.of());

        List<NearbyBusStopResult> result = service.getNearbyBusStops(37.5665, 126.9780, 5);

        assertThat(result).isEmpty();
        // 500, 1000, 1500, 2000, 2500, 3000 총 6번 호출
        then(busStopRepository).should(org.mockito.Mockito.times(6))
                .findNearby(anyDouble(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("중간 반경에서 결과를 찾으면 그 이후 반경은 조회하지 않는다")
    void getNearbyBusStops_stopsExpandingWhenResultsFound() {
        NearbyBusStopProjection projection = projection(
                "2001", "광화문", "02-001", 37.5720, 126.9769, 980.0
        );

        given(busStopRepository.findNearby(37.5665, 126.9780, 500.0, 5)).willReturn(List.of());
        given(busStopRepository.findNearby(37.5665, 126.9780, 1000.0, 5)).willReturn(List.of(projection));
        given(predictionSupportService.supportedRouteIds()).willReturn(Set.of());
        // routeIds가 비어 findSupportedStopIds는 호출되지 않는다(NearbyTransitService의 빈 컬렉션 가드).

        List<NearbyBusStopResult> result = service.getNearbyBusStops(37.5665, 126.9780, 5);

        assertThat(result).hasSize(1);
        // 1000m에서 찾았으므로 1500m 이후는 호출하지 않음
        then(busStopRepository).should(org.mockito.Mockito.times(2))
                .findNearby(anyDouble(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("위도가 범위를 벗어나면 예외를 던지고 조회를 수행하지 않는다")
    void getNearbyBusStops_throwsWhenLatitudeIsOutOfRange() {
        assertThatThrownBy(() -> service.getNearbyBusStops(91.0, 126.9780, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("lat must be between -90 and 90");

        then(busStopRepository).shouldHaveNoInteractions();
        then(busRouteStopRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("경도가 범위를 벗어나면 예외를 던지고 조회를 수행하지 않는다")
    void getNearbyBusStops_throwsWhenLongitudeIsOutOfRange() {
        assertThatThrownBy(() -> service.getNearbyBusStops(37.5665, 181.0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("lng must be between -180 and 180");

        then(busStopRepository).shouldHaveNoInteractions();
        then(busRouteStopRepository).shouldHaveNoInteractions();
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

    @Test
    @DisplayName("근처 지하철역에 GTFS 정류장 id 를 싣는다(#298), 모르면 null")
    void nearbySubwayCarriesGtfsStopId() {
        watoo.grd.nextroute.domain.subway.repository.NearbySubwayStationProjection gangnam =
                org.mockito.Mockito.mock(watoo.grd.nextroute.domain.subway.repository.NearbySubwayStationProjection.class);
        org.mockito.BDDMockito.given(gangnam.getStationId()).willReturn("1002000222");
        org.mockito.BDDMockito.given(gangnam.getLineId()).willReturn("1002");
        org.mockito.BDDMockito.given(gangnam.getDistMeters()).willReturn(80.0);
        org.mockito.BDDMockito.given(gangnam.getLatitude()).willReturn(37.4979);
        org.mockito.BDDMockito.given(gangnam.getLongitude()).willReturn(127.0276);
        org.mockito.BDDMockito.given(subwayStationRepository.findNearby(
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyInt()))
                .willReturn(List.of(gangnam));
        org.mockito.BDDMockito.given(subwayGtfsStopIndex.gtfsStopId("1002", "1002000222"))
                .willReturn(java.util.Optional.of("1:RS_ACC1_S-1-0222"));

        assertThat(service.getNearbySubwayStations(37.4979, 127.0276, 5).get(0).gtfsStopId())
                .isEqualTo("1:RS_ACC1_S-1-0222");
    }
}
