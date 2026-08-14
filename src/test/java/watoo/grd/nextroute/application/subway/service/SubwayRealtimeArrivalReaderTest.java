package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.application.subway.port.out.SubwayApiPort;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 방향 필터의 회귀 계약.
 *
 * <p>{@code toDirection} 이 엄격해졌다({@code wayCode} 의 모르는 값이 {@code null} 이 된다).
 * 그 변화가 <b>대기 지하철 ETA 경로</b>까지 새는지 여기서 고정한다 — 그쪽은 방향이 {@code null}
 * 이면 필터를 건너뛰어 전 방향을 그대로 보여 주는 것이 원래 동작이고, 그게 바뀌면 도착 정보가
 * 통째로 사라진다. 조용히 비는 종류의 회귀라 시험 없이는 안 드러난다.
 */
class SubwayRealtimeArrivalReaderTest {

    private static final String LINE = "1001";
    private static final String STATION = "1001000133";

    private final SubwayApiPort apiPort = mock(SubwayApiPort.class);
    private final SubwayRealtimeArrivalReader reader =
            new SubwayRealtimeArrivalReader(mock(SubwayRealtimeCachePort.class), apiPort);

    @Test
    @DisplayName("방향을 알면 그 방향만 남긴다")
    void filtersByKnownDirection() {
        givenBothDirections();

        List<SubwayRealtimeArrivalReader.Arrival> arrivals =
                reader.arrivalsByStation(LINE, STATION, "서울역", 1);

        assertThat(arrivals).hasSize(1);
        assertThat(arrivals.get(0).train().getDirection()).isEqualTo("상행");
    }

    @Test
    @DisplayName("방향을 모르면 전 방향을 그대로 본다 — 필터를 건너뛰는 것이 원래 동작이다")
    void keepsEveryDirectionWhenWayCodeIsUnknown() {
        givenBothDirections();

        assertThat(reader.arrivalsByStation(LINE, STATION, "서울역", 99)).hasSize(2);
        assertThat(reader.arrivalsByStation(LINE, STATION, "서울역", null)).hasSize(2);
    }

    private void givenBothDirections() {
        when(apiPort.getRealtimeArrivalByStation(anyString()))
                .thenReturn(List.of(arrival("1234", "상행", 120), arrival("5678", "하행", 200)));
    }

    private SubwayArrivalInfo arrival(String trainNo, String direction, int seconds) {
        return new SubwayArrivalInfo(STATION, "서울역", LINE, direction, null, null, null,
                null, null, null, "0", seconds, trainNo, null, null, null, null, null, null,
                null, null, null);
    }
}
