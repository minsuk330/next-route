package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.port.out.SubwaySegmentTimePort;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("구간 실측 기록기")
class SubwaySegmentTimeRecorderTest {

    private final SubwaySegmentTimePort port = mock(SubwaySegmentTimePort.class);
    private final SubwaySegmentTimeRecorder recorder = new SubwaySegmentTimeRecorder(port);

    {
        ReflectionTestUtils.setField(recorder, "enabled", true);
    }

    @Test
    @DisplayName("출발은 직전 회차가 출발 전(5·4·99)일 때만, 도착(1)·진입(0)은 매번. 시각은 recptnDt(KST)")
    void mapsCodes() {
        Instant at = Instant.parse("2026-10-07T13:10:11Z");
        recorder.record(List.of(row("5")));
        recorder.record(List.of(row("3")));
        recorder.record(List.of(row("1")));
        recorder.record(List.of(row("0")));

        verify(port).recordDeparted("1002", "내선", "1002000237", "7462", at);
        verify(port, org.mockito.Mockito.times(2)).recordArrived("1002", "내선", "1002000237", "7462", at);
    }

    @Test
    @DisplayName("재시작 첫 회차·직전에 안 보였던 열차의 전역 출발은 기록하지 않는다(소요시간이 15초로 짧게 남던 문제)")
    void firstSightingOfDepartureIsNotADeparture() {
        recorder.record(List.of(row("3")));
        recorder.record(List.of(row("3")));

        verify(port, never()).recordDeparted(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("꺼져 있거나 값이 비면 아무것도 안 한다")
    void skipsWhenDisabledOrIncomplete() {
        ReflectionTestUtils.setField(recorder, "enabled", false);
        recorder.record(List.of(row("3")));
        ReflectionTestUtils.setField(recorder, "enabled", true);
        recorder.record(List.of(SubwayRealtimeTrain.builder().arrivalCode("3").build()));

        verify(port, never()).recordDeparted(anyString(), anyString(), anyString(), anyString(), any());
    }

    private SubwayRealtimeTrain row(String code) {
        return SubwayRealtimeTrain.builder().lineId("1002").direction("내선").stationId("1002000237")
                .trainNo("7462").arrivalCode(code).receivedAt("2026-10-07 22:10:11").build();
    }
}
