package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.port.out.SubwayArrivalDepartureLogPort;
import watoo.grd.nextroute.application.subway.port.out.SubwayArrivalDepartureLogPort.Row;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("도착 API 출발 기록기(#317-4)")
class SubwayArrivalDepartureRecorderTest {

    private static final String LINE = "1075";
    private static final String STATION = "1075000210";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);
    /** recptnDt 2026-10-07 22:10:11 KST */
    private static final Instant RECEIVED = Instant.parse("2026-10-07T13:10:11Z");

    private final SubwayArrivalDepartureLogPort port = mock(SubwayArrivalDepartureLogPort.class);
    private final SubwayArrivalDepartureRecorder recorder =
            new SubwayArrivalDepartureRecorder(port, new ServiceDateResolver());

    {
        ReflectionTestUtils.setField(recorder, "enabled", true);
        ReflectionTestUtils.setField(recorder, "lineIds", Set.of(LINE));
    }

    @Test
    @DisplayName("도착(1) → 출발(2) 이면 출발 시각 = 그 행의 recptnDt")
    void arrivedThenDeparted() {
        recorder.record(List.of(row("K1", "1")), RECEIVED);
        recorder.record(List.of(row("K1", "2")), RECEIVED.plusSeconds(15));

        verify(port).append(TODAY, LINE, "상행", STATION, new Row("K1", "청량리", "일반", RECEIVED));
    }

    @Test
    @DisplayName("진입(0)·도착(1) 뒤 행이 사라지면 직전 관측과 이번 수집의 가운데")
    void disappearedAfterArrival() {
        recorder.record(List.of(row("K1", "0"), row("K2", "99")), RECEIVED.plusSeconds(10));
        recorder.record(List.of(row("K2", "99")), RECEIVED.plusSeconds(40));

        verify(port).append(TODAY, LINE, "상행", STATION, new Row("K1", "청량리", "일반", RECEIVED.plusSeconds(20)));
    }

    @Test
    @DisplayName("재시작 첫 회차의 출발(2)·운행중(99)에서 사라진 열차는 기록하지 않는다")
    void needsAnObservedTransition() {
        recorder.record(List.of(row("K1", "2"), row("K2", "99")), RECEIVED);
        recorder.record(List.of(row("K1", "2")), RECEIVED.plusSeconds(15));

        verify(port, never()).append(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("대상 노선이 아니면(위치 API 가 있는 노선) 무시")
    void ignoresOtherLines() {
        SubwayRealtimeTrain other = SubwayRealtimeTrain.builder().lineId("1002").direction("내선")
                .stationId("1002000222").trainNo("2231").arrivalCode("1").receivedAt("2026-10-07 22:10:11").build();
        recorder.record(List.of(other), RECEIVED);
        recorder.record(List.of(), RECEIVED.plusSeconds(15));

        verify(port, never()).append(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("행 수가 직전의 절반 미만이면 부분 응답으로 보고 회차를 건너뛴다 — 다음 정상 회차에서 이어 본다")
    void skipsPartialResponse() {
        List<SubwayRealtimeTrain> full = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            full.add(row("K" + i, "1"));
        }
        recorder.record(full, RECEIVED);
        recorder.record(List.of(row("K0", "1")), RECEIVED.plusSeconds(15));
        verify(port, never()).append(any(), any(), any(), any(), any());

        List<SubwayRealtimeTrain> next = new ArrayList<>(full);
        next.set(0, row("K0", "2"));
        recorder.record(next, RECEIVED.plusSeconds(30));
        verify(port).append(TODAY, LINE, "상행", STATION, new Row("K0", "청량리", "일반", RECEIVED));
    }

    private static SubwayRealtimeTrain row(String trainNo, String code) {
        return SubwayRealtimeTrain.builder()
                .lineId(LINE).direction("상행").stationId(STATION).trainNo(trainNo).arrivalCode(code)
                .receivedAt("2026-10-07 22:10:11").destinationName("청량리").trainType("일반")
                .build();
    }
}
