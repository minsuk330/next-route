package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import watoo.grd.nextroute.application.subway.config.SubwayPositionProperties;
import watoo.grd.nextroute.application.subway.dto.SubwayPositionFetchResult;
import watoo.grd.nextroute.application.subway.port.out.SubwayApiPort;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 수집기가 남기는 요약 로그.
 *
 * <p>차단은 차단되는 순간 {@code [ApiBlocker] ... BLOCKED} 로 한 번 남는다. 그 뒤 자정까지 매 회차
 * (15초)마다 "N/16 노선 실패"를 또 찍으면 시간당 240줄이 쌓이고, 새 장애처럼 보여 사람을 부른다
 * (2026-09-29 하루 3,230줄). 요약에는 <b>이번 회차에 새로 실패한 것</b>만 센다.
 */
@ExtendWith(OutputCaptureExtension.class)
class RealtimeSubwayPositionCollectorTest {

    private final SubwayApiPort api = mock(SubwayApiPort.class);
    private final SubwayTrainTransitionTracker tracker = mock(SubwayTrainTransitionTracker.class);
    private RealtimeSubwayPositionCollector collector;

    @BeforeEach
    void setUp() {
        SubwayPositionProperties properties = new SubwayPositionProperties();
        properties.setEnabled(true);
        properties.setLines(List.of("1001", "1002"));
        // 시작=끝이면 쉬는 시간이 없다. 테스트가 시계에 매이지 않게 한다.
        properties.setActiveHoursStart("00:00");
        properties.setActiveHoursEnd("00:00");
        collector = new RealtimeSubwayPositionCollector(api, tracker, properties);
    }

    @Test
    @DisplayName("막혀서 건너뛴 노선은 실패로 세지 않는다 — 매 회차 경보가 쌓이지 않는다")
    void blockedLinesAreNotReportedEveryRound(CapturedOutput output) {
        given(api.getRealtimePosition(anyString())).willAnswer(call ->
                SubwayPositionFetchResult.blocked(call.getArgument(0), Instant.now()));

        collector.collect();

        assertThat(output).doesNotContain("노선 실패");
    }

    @Test
    @DisplayName("이번 회차에 실제로 실패한 노선은 지금처럼 남긴다")
    void realFailuresAreStillReported(CapturedOutput output) {
        given(api.getRealtimePosition("1001")).willReturn(
                SubwayPositionFetchResult.failed("1001", Instant.now(), "CALL_FAILED"));
        given(api.getRealtimePosition("1002")).willReturn(
                SubwayPositionFetchResult.blocked("1002", Instant.now()));

        collector.collect();

        assertThat(output).contains("1/2 노선 실패");
    }

    @Test
    @DisplayName("열차가 없는 노선은 실패가 아니다")
    void noTrainsIsNotAFailure(CapturedOutput output) {
        given(api.getRealtimePosition(anyString())).willAnswer(call ->
                SubwayPositionFetchResult.noTrains(call.getArgument(0), Instant.now()));

        collector.collect();

        assertThat(output).doesNotContain("노선 실패");
    }
}
