package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.dto.OtpRailRoute;
import watoo.grd.nextroute.application.subway.dto.RailPattern;
import watoo.grd.nextroute.application.subway.port.out.SubwayRoutePatternPort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SubwayLinePatternServiceTest {

    private static final OtpRailRoute LINE5_DOWN = new OtpRailRoute("1:RR_ACC1_S-1-05-1D", "서울5호선", "서울5호선<하행>");
    private static final RailPattern PATTERN = new RailPattern(LINE5_DOWN.gtfsId(), 95, List.of(
            new RailPattern.Stop("방화", 0, 0, null, 100),
            new RailPattern.Stop("개화산", 0, 0, 220, 250)));

    private final SubwayRoutePatternPort port = mock(SubwayRoutePatternPort.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T00:00:00Z"));
    private final SubwayLinePatternService service = new SubwayLinePatternService(port, clock);

    @Test
    @DisplayName("하루 안에는 다시 부르지 않는다")
    void cachesForADay() {
        givenOtpUp();

        service.patterns("1005", "하행");
        clock.advance(Duration.ofHours(23));
        service.patterns("1005", "하행");

        verify(port, times(1)).patterns(LINE5_DOWN.gtfsId());
        verify(port, times(1)).subwayRoutes();
    }

    @Test
    @DisplayName("하루가 지나면 새로 받는다")
    void reloadsAfterTtl() {
        givenOtpUp();

        service.patterns("1005", "하행");
        clock.advance(Duration.ofHours(25));
        service.patterns("1005", "하행");

        verify(port, times(2)).patterns(LINE5_DOWN.gtfsId());
    }

    @Test
    @DisplayName("새로 고치다 실패하면 낡은 값을 준다 — 빈 목록은 '종착'과 구분되지 않는다")
    void servesStaleOnFailure() {
        givenOtpUp();
        service.patterns("1005", "하행");
        clock.advance(Duration.ofHours(25));
        given(port.subwayRoutes()).willThrow(new IllegalStateException("OTP down"));

        assertThat(service.patterns("1005", "하행")).hasSize(1);
    }

    @Test
    @DisplayName("낡은 값도 없으면 실패를 올린다")
    void throwsWithoutAnyCache() {
        given(port.subwayRoutes()).willThrow(new IllegalStateException("OTP down"));

        assertThatThrownBy(() -> service.patterns("1005", "하행")).hasMessage("OTP down");
    }

    @Test
    @DisplayName("그 방향 노선이 없으면 빈 목록")
    void emptyWhenNoRoute() {
        givenOtpUp();

        assertThat(service.patterns("1005", "내선")).isEmpty();
    }

    private void givenOtpUp() {
        given(port.subwayRoutes()).willReturn(List.of(LINE5_DOWN));
        given(port.patterns(LINE5_DOWN.gtfsId())).willReturn(List.of(PATTERN));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration duration) {
            now.updateAndGet(i -> i.plus(duration));
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
