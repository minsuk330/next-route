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
    private final SubwaySegmentTimeRecorder recorder =
            new SubwaySegmentTimeRecorder(port, SubwayExpressFixtures.classifier());

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

    @Test
    @DisplayName("급행 열차는 표본에서 뺀다 — 같은 열차의 다른 역 행이 '일반'이어도(열차 단위). 4호선 급행은 번호로")
    void excludesNonNormalTrainsPerTrain() {
        // 9호선 9687: 중앙보훈병원 행은 '급행', 올림픽공원 행은 '일반'으로 온다(행마다 다름)
        SubwayRealtimeTrain expressRow = train("1009", "9687", "1009000938", "급행", "1");
        SubwayRealtimeTrain flippedRow = train("1009", "9687", "1009000937", "일반", "1");
        // 4호선 4405: 행은 전부 '일반'이지만 번호(44xx)로 급행
        SubwayRealtimeTrain line4 = train("1004", "4405", "1004000415", "일반", "1");
        // 9호선 근거 없는 행: 급행 노선이라 일반으로 보지 않는다
        SubwayRealtimeTrain unknownOnExpressLine = train("1009", "9001", "1009000920", null, "1");

        recorder.record(List.of(expressRow, flippedRow, line4, unknownOnExpressLine));

        verify(port, never()).recordArrived(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("일반 열차·1호선 구로 급행(14xx, 모든 역 정차)·일반만 다니는 노선의 종류 모름은 남긴다")
    void keepsNormalTrains() {
        recorder.record(List.of(train("1009", "9100", "1009000920", "일반", "1"),
                train("1001", "1406", "1001000139", "급행", "1"),
                train("1002", "2001", "1002000222", null, "1")));

        verify(port).recordArrived(org.mockito.ArgumentMatchers.eq("1009"), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq("9100"), any());
        verify(port).recordArrived(org.mockito.ArgumentMatchers.eq("1001"), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq("1406"), any());
        verify(port).recordArrived(org.mockito.ArgumentMatchers.eq("1002"), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq("2001"), any());
    }

    private static SubwayRealtimeTrain train(String lineId, String trainNo, String stationId, String trainType,
                                             String code) {
        return SubwayRealtimeTrain.builder().lineId(lineId).direction("상행").stationId(stationId).trainNo(trainNo)
                .trainType(trainType).arrivalCode(code).receivedAt("2026-10-07 22:10:11").build();
    }

    private SubwayRealtimeTrain row(String code) {
        return SubwayRealtimeTrain.builder().lineId("1002").direction("내선").stationId("1002000237")
                .trainNo("7462").arrivalCode(code).receivedAt("2026-10-07 22:10:11").build();
    }
}
