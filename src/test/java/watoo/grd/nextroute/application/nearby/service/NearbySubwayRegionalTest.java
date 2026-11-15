package watoo.grd.nextroute.application.nearby.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.nearby.dto.NearbySubwayStationResult;
import watoo.grd.nextroute.application.regionalsubway.config.RegionalSubwayServingProperties;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalStationSeedCatalog;
import watoo.grd.nextroute.application.subway.service.SubwayGtfsStopIndex;
import watoo.grd.nextroute.domain.subway.repository.NearbySubwayStationProjection;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 근처 지하철역에 지방 역을 섞는 계약. 옛 앱(includeRegional 없음) 응답은 그대로여야 한다. */
class NearbySubwayRegionalTest {

    private final SubwayStationRepository repo = mock(SubwayStationRepository.class);
    private final SubwayGtfsStopIndex gtfs = mock(SubwayGtfsStopIndex.class);
    private final RegionalSubwayServingProperties serving = new RegionalSubwayServingProperties();
    private final RegionalStationSeedCatalog catalog = new RegionalStationSeedCatalog(new ObjectMapper());
    private final NearbyTransitService service = new NearbyTransitService(null, null, repo, null, gtfs, catalog, serving);

    // 부산 서면역 앞(서울 역 없음)
    private static final double LAT = 35.1577;
    private static final double LNG = 129.0591;

    @Test
    @DisplayName("includeRegional 없으면 지방 역을 섞지 않고 arrivalSource 도 싣지 않는다 — JSON 이 지금과 같다")
    void legacyUnchanged() throws Exception {
        serving.setEnabled(true);
        when(repo.findNearby(anyDouble(), anyDouble(), anyDouble(), anyInt())).thenReturn(List.of());
        assertThat(service.getNearbySubwayStations(LAT, LNG, 5)).isEmpty();

        NearbySubwayStationResult seoul = new NearbySubwayStationResult("0150", "서울역", "1001", "1호선", 37.5, 126.9, 10, "1:x", null);
        assertThat(new ObjectMapper().writeValueAsString(seoul)).doesNotContain("arrivalSource");
    }

    @Test
    @DisplayName("서비스 경로 그대로 — includeRegional 생략 시 서울 항목 JSON 키가 기존 8개 그대로(arrivalSource 없음, 지방 역 없음)")
    void legacyJsonThroughService() throws Exception {
        serving.setEnabled(true);
        NearbySubwayStationProjection p = mock(NearbySubwayStationProjection.class);
        when(p.getStationId()).thenReturn("S1");
        when(p.getStationName()).thenReturn("가상");
        when(p.getLineId()).thenReturn("1001");
        when(p.getLineName()).thenReturn("1호선");
        when(p.getLatitude()).thenReturn(LAT);
        when(p.getLongitude()).thenReturn(LNG);
        when(p.getDistMeters()).thenReturn(450.0);
        when(repo.findNearby(anyDouble(), anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(p));
        when(gtfs.gtfsStopId(anyString(), anyString())).thenReturn(Optional.of("1:seoul"));

        List<NearbySubwayStationResult> out = service.getNearbySubwayStations(LAT, LNG, 5);
        assertThat(out).hasSize(1);
        var node = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(out.get(0)));
        assertThat(node.properties()).extracting(java.util.Map.Entry::getKey).containsExactly(
                "stationId", "stationName", "lineId", "lineName", "latitude", "longitude", "distanceMeters", "gtfsStopId");
    }

    @Test
    @DisplayName("includeRegional + serving on: 지방 역을 거리순으로 섞고 TIMETABLE, gtfsStopId 는 null, stationId = stationKey")
    void mergesRegional() {
        serving.setEnabled(true);
        NearbySubwayStationProjection p = mock(NearbySubwayStationProjection.class);
        when(p.getStationId()).thenReturn("S1");
        when(p.getStationName()).thenReturn("가상");
        when(p.getLineId()).thenReturn("1001");
        when(p.getLineName()).thenReturn("1호선");
        when(p.getLatitude()).thenReturn(LAT);
        when(p.getLongitude()).thenReturn(LNG);
        when(p.getDistMeters()).thenReturn(450.0);
        when(repo.findNearby(anyDouble(), anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(p));
        when(gtfs.gtfsStopId(anyString(), anyString())).thenReturn(Optional.of("1:seoul"));

        List<NearbySubwayStationResult> out = service.getNearbySubwayStations(LAT, LNG, 3, true);

        assertThat(out).hasSize(3);
        assertThat(out.get(0).stationId()).isIn("BS1-119", "BS2-219");
        assertThat(out.get(0).arrivalSource()).isEqualTo("TIMETABLE");
        assertThat(out.get(0).gtfsStopId()).isNull();
        assertThat(out).extracting(NearbySubwayStationResult::distanceMeters).isSorted();
        assertThat(out).filteredOn(r -> r.stationId().equals("S1")).allMatch(r -> "REALTIME".equals(r.arrivalSource()));
    }

    @Test
    @DisplayName("serving off 면 includeRegional 이어도 지방 역을 섞지 않는다")
    void servingOff() {
        serving.setEnabled(false);
        when(repo.findNearby(anyDouble(), anyDouble(), anyDouble(), anyInt())).thenReturn(List.of());
        assertThat(service.getNearbySubwayStations(LAT, LNG, 5, true)).isEmpty();
    }
}
