package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.dto.DirectedRailPattern;
import watoo.grd.nextroute.application.subway.dto.RailPattern;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("서울 역 id → GTFS 정류장 id(캐시만)")
class SubwayGtfsStopIndexTest {

    private final SubwayLinePatternService patterns = mock(SubwayLinePatternService.class);
    private final SubwayLineStationIndex stations = mock(SubwayLineStationIndex.class);
    private final SubwayGtfsStopIndex index = new SubwayGtfsStopIndex(patterns, stations);

    private static final List<DirectedRailPattern> INNER = List.of(new DirectedRailPattern(
            "1:RR_ACC1_S-1-02-1I", "서울2호선(본선)<내선>", true, 3, List.of(
            new RailPattern.Stop("강남(2호선)", 37.4979, 127.0276, 0, 0, "1:RS_ACC1_S-1-0222"),
            new RailPattern.Stop("역삼", 37.5006, 127.0364, 60, 60, "1:RS_ACC1_S-1-0221"))));

    @Test
    @DisplayName("적재된 패턴이 없으면 비어 있고 OTP 를 부르지 않는다")
    void emptyWithoutCache() {
        given(patterns.cachedPatterns(anyString(), anyString())).willReturn(List.of());

        assertThat(index.gtfsStopId("1002", "1002000222")).isEmpty();
        verify(patterns, never()).patterns(anyString(), anyString());
    }

    @Test
    @DisplayName("적재된 패턴 정차를 역 마스터에 이어 GTFS id 를 준다, 같은 원본이면 다시 잇지 않는다")
    void mapsFromCachedPatterns() {
        given(patterns.cachedPatterns("1002", "내선")).willReturn(INNER);
        given(patterns.cachedPatterns("1002", "외선")).willReturn(List.of());
        given(stations.candidates("1002")).willReturn(List.of(
                new SubwayPatternStationMatcher.Candidate("1002000222", "강남", 37.4979, 127.0276),
                new SubwayPatternStationMatcher.Candidate("1002000221", "역삼", 37.5006, 127.0364)));

        assertThat(index.gtfsStopId("1002", "1002000222")).contains("1:RS_ACC1_S-1-0222");
        assertThat(index.gtfsStopId("1002", "1002000221")).contains("1:RS_ACC1_S-1-0221");
        assertThat(index.gtfsStopId("1002", "1002000999")).isEmpty();
        verify(stations, times(1)).candidates("1002");
    }
}
