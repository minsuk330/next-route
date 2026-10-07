package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SubwayLinePatternWarmupTest {

    private final SubwayLinePatternService service = mock(SubwayLinePatternService.class);
    private final SubwayLinePatternWarmup warmup = new SubwayLinePatternWarmup(service);

    @Test
    @DisplayName("전 노선 × 방향을 데운다 — 2호선만 내·외선")
    void warmsEveryLineAndDirection() {
        warmup.warmUp();

        int lines = SubwayRoutePatternAssembler.LINE_IDS.size();
        verify(service, times(lines * 2)).patterns(any(), any());
        verify(service).patterns("1002", "내선");
        verify(service).patterns("1002", "외선");
        verify(service).patterns("1077", "하행");
        verify(service, times(0)).patterns("1077", "내선");
    }

    @Test
    @DisplayName("한 노선이 실패해도 나머지를 계속 데운다")
    void continuesAfterFailure() {
        given(service.patterns(eq("1001"), any())).willThrow(new IllegalStateException("OTP down"));

        warmup.warmUp();

        verify(service).patterns("1009", "상행");
    }

    @Test
    void directionsFollowLoopLine() {
        assertThat(SubwayLinePatternWarmup.directions("1002")).containsExactly("내선", "외선");
        assertThat(SubwayLinePatternWarmup.directions("1005")).containsExactly("상행", "하행");
    }
}
