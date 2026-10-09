package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.service.StationsAheadCalculator.Pattern;
import watoo.grd.nextroute.application.subway.service.StationsAheadCalculator.Stop;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class SubwayLineOrderTest {

    @Test
    @DisplayName("한 패턴이면 바로 앞·뒤 역")
    void simpleNeighbors() {
        List<Pattern> patterns = List.of(pattern(10, "A", "B", "C"));

        assertThat(SubwayLineOrder.neighbor(patterns, "B", -1)).contains("A");
        assertThat(SubwayLineOrder.neighbor(patterns, "B", +1)).contains("C");
        assertThat(SubwayLineOrder.neighbor(patterns, "A", -1)).isEmpty(); // 기점
        assertThat(SubwayLineOrder.neighbor(patterns, "C", +1)).isEmpty(); // 종착
    }

    @Test
    @DisplayName("급행이 건너뛴 역은 후보에서 빠진다 — 완행 A→B→X, 급행 A→X 이면 X 앞은 B")
    void expressSkipDoesNotMakeItAmbiguous() {
        List<Pattern> patterns = List.of(pattern(100, "A", "B", "X", "Y", "Z"), pattern(30, "A", "X", "Z"));

        assertThat(SubwayLineOrder.neighbor(patterns, "X", -1)).contains("B");
        assertThat(SubwayLineOrder.neighbor(patterns, "X", +1)).contains("Y");
    }

    @Test
    @DisplayName("갈래 합류역 직후는 모름 — 갈래마다 전역이 다르다(1호선 구로)")
    void junctionIsUnknown() {
        List<Pattern> patterns = List.of(pattern(50, "C1", "X", "Y"), pattern(50, "C2", "X", "Y"));

        assertThat(SubwayLineOrder.neighbor(patterns, "X", -1)).isEmpty();
        assertThat(SubwayLineOrder.neighbor(patterns, "X", +1)).contains("Y");
    }

    @Test
    @DisplayName("순환 패턴(한 패턴 여러 바퀴)도 같은 답")
    void loopPattern() {
        List<Pattern> patterns = List.of(pattern(100, "A", "B", "C", "A", "B", "C"));

        assertThat(SubwayLineOrder.neighbor(patterns, "A", -1)).contains("C");
        assertThat(SubwayLineOrder.neighbor(patterns, "C", +1)).contains("A");
    }

    @Test
    @DisplayName("역에 못 이은 정차(stationId null)는 이웃이 되지 못한다")
    void unmatchedStopIsNotANeighbor() {
        List<Pattern> patterns = List.of(new Pattern("R", "R", false, 1, List.of(
                new Stop("name:가", null, "가", 0, 0), new Stop("X", "X", "X", 60, 60))));

        assertThat(SubwayLineOrder.neighbor(patterns, "X", -1)).isEmpty();
    }

    @Test
    @DisplayName("패턴이 아직 없으면 모름으로 답하고 OTP 적재는 뒤에서 한 번만")
    void cacheMissLoadsInBackgroundOnce() {
        SubwayLinePatternService patternService = mock(SubwayLinePatternService.class);
        given(patternService.cachedPatterns("1002", "내선")).willReturn(List.of());
        given(patternService.patterns("1002", "내선")).willAnswer(invocation -> {
            Thread.sleep(200);
            return List.of();
        });
        SubwayLineOrder order = new SubwayLineOrder(patternService, mock(SubwayLineStationIndex.class),
                mock(SubwayStationsAheadService.class));

        Optional<String> first = order.prevOf("1002", "내선", "X");
        order.prevOf("1002", "내선", "X");
        order.nextOf("1002", "내선", "X");

        assertThat(first).isEmpty();
        verify(patternService, timeout(1000).times(1)).patterns("1002", "내선");
    }

    private static Pattern pattern(int trips, String... ids) {
        List<Stop> stops = Arrays.stream(ids).map(id -> new Stop(id, id, id, 0, 0)).toList();
        return new Pattern("R" + trips, "R", false, trips, stops);
    }
}
