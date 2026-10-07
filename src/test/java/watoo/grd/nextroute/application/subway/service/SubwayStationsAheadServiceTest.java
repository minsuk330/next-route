package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationsAheadResponse;
import watoo.grd.nextroute.application.subway.dto.DirectedRailPattern;
import watoo.grd.nextroute.application.subway.dto.RailPattern;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.SubwayErrorCode;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SubwayStationsAheadServiceTest {

    private final SubwayStationRepository repository = mock(SubwayStationRepository.class);
    private final SubwayLinePatternService patterns = mock(SubwayLinePatternService.class);
    private final SubwayLineStationIndex index = mock(SubwayLineStationIndex.class);
    private final SubwayStationsAheadService service = new SubwayStationsAheadService(repository, patterns, index);

    private static final SubwayPatternStationMatcher.Candidate GANGNAM =
            new SubwayPatternStationMatcher.Candidate("1077000687", "강남", 37.4966, 127.0284);
    private static final SubwayPatternStationMatcher.Candidate YANGJAE =
            new SubwayPatternStationMatcher.Candidate("1077000688", "양재", 37.4844, 127.0346);

    @Test
    @DisplayName("남은 역에 역 ID·분·환승을 붙여 준다")
    void buildsResponse() {
        givenBoard();
        given(patterns.patterns("1077", "하행")).willReturn(List.of(new DirectedRailPattern(
                "1:RR_ACC1_S-1-SB-1D", "신분당선<하행>", false, 159, List.of(
                new RailPattern.Stop("강남(신분당선)", 37.4966, 127.0284, null, 1000),
                new RailPattern.Stop("양재(신분당선)", 37.4844, 127.0346, 1120, 1140)))));
        given(index.candidates("1077")).willReturn(List.of(GANGNAM, YANGJAE));
        given(index.transfers("양재", 37.4844, 127.0346, "1077"))
                .willReturn(List.of(new SubwayLineStationIndex.Transfer("1003", "3호선")));

        SubwayStationsAheadResponse r = service.getStationsAhead("1077000687", "하행", null);

        assertThat(r.lineId()).isEqualTo("1077");
        assertThat(r.boardStationName()).isEqualTo("강남");
        assertThat(r.endReason()).isEqualTo(SubwayStationsAheadResponse.EndReason.TERMINAL);
        SubwayStationsAheadResponse.Station yangjae = r.stations().get(0);
        assertThat(yangjae.stationId()).isEqualTo("1077000688");
        assertThat(yangjae.stationName()).isEqualTo("양재");
        assertThat(yangjae.stopsAway()).isEqualTo(1);
        assertThat(yangjae.minutes()).isEqualTo(2);
        assertThat(yangjae.transfers()).containsExactly(new SubwayStationsAheadResponse.Transfer("1003", "3호선"));
    }

    @Test
    void unknownStationIs404() {
        given(repository.findByStationId("x")).willReturn(Optional.empty());

        assertCode(() -> service.getStationsAhead("x", "하행", null), SubwayErrorCode.SUBWAY_STATION_NOT_FOUND);
    }

    @Test
    @DisplayName("방향 값 공간 밖 → 400, OTP 는 묻지 않는다")
    void unknownDirectionIs400() {
        givenBoard();

        assertCode(() -> service.getStationsAhead("1077000687", "up", null), SubwayErrorCode.SUBWAY_DIRECTION_INVALID);
        verify(patterns, never()).patterns(any(), any());
    }

    @Test
    @DisplayName("그 노선에 그 방향이 없으면 400 — 신분당선에 내선은 없다")
    void directionNotOnLineIs400() {
        givenBoard();
        given(patterns.patterns("1077", "내선")).willReturn(List.of());

        assertCode(() -> service.getStationsAhead("1077000687", "내선", null), SubwayErrorCode.SUBWAY_DIRECTION_INVALID);
    }

    @Test
    @DisplayName("승차역이 운행 패턴에 없으면 422")
    void boardNotOnRouteIs422() {
        givenBoard();
        given(patterns.patterns("1077", "하행")).willReturn(List.of(new DirectedRailPattern(
                "R", "신분당선<하행>", false, 1, List.of(
                new RailPattern.Stop("양재", 37.4844, 127.0346, 0, 0),
                new RailPattern.Stop("판교", 37.3948, 127.1112, 60, 60)))));
        given(index.candidates("1077")).willReturn(List.of(GANGNAM, YANGJAE));

        assertCode(() -> service.getStationsAhead("1077000687", "하행", null), SubwayErrorCode.SUBWAY_STATION_NOT_ON_ROUTE);
    }

    @Test
    @DisplayName("OTP 실패(낡은 값도 없음) → 503")
    void otpFailureIs503() {
        givenBoard();
        given(patterns.patterns("1077", "하행")).willThrow(new IllegalStateException("OTP down"));

        assertCode(() -> service.getStationsAhead("1077000687", "하행", null), SubwayErrorCode.SUBWAY_ROUTE_UNAVAILABLE);
    }

    private void givenBoard() {
        SubwayStation board = SubwayLineStationIndexTest.station("1077000687", "강남", "1077", "신분당선", 37.4966, 127.0284);
        given(repository.findByStationId("1077000687")).willReturn(Optional.of(board));
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, SubwayErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
