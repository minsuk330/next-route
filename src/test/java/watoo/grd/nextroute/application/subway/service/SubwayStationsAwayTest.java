package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalCode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 거리 표기의 계약.
 *
 * <p>{@code barvlDt=0} 인 열차에 남는 정보가 이것뿐이라, 여기가 비면 화면에 아무것도 못 쓴다.
 */
class SubwayStationsAwayTest {

    @ParameterizedTest(name = "{0} → {1}정거장")
    @CsvSource({
            "ENTERING, 0",
            "ARRIVED, 0",
            "PREV_DEPARTED, 1",
            "PREV_ENTERING, 1",
            "PREV_ARRIVED, 1",
    })
    @DisplayName("코드만으로 답이 나오는 자리는 문구를 보지 않는다")
    void resolvesFromCodeAlone(SubwayArrivalCode code, int expected) {
        assertThat(SubwayStationsAway.resolve(code, null)).isEqualTo(expected);
        // 문구가 무엇이든 코드가 이긴다 — 원천이 표현을 바꿔도 거리 표기가 흔들리면 안 된다.
        assertThat(SubwayStationsAway.resolve(code, "무슨 말인지 모를 문구")).isEqualTo(expected);
    }

    @Test
    @DisplayName("운행 중은 문구에서 '[N]번째 전역'을 읽는다")
    void readsNthPreviousStationFromMessage() {
        assertThat(SubwayStationsAway.resolve(SubwayArrivalCode.ENROUTE, "[3]번째 전역")).isEqualTo(3);
        assertThat(SubwayStationsAway.resolve(SubwayArrivalCode.ENROUTE, "[2]번째 전역 (강남)")).isEqualTo(2);
    }

    @Test
    @DisplayName("시간으로 답한 운행 중은 거리가 없다 — 초가 있으므로 손해가 없다")
    void hasNoDistanceWhenSourceAnsweredWithTime() {
        assertThat(SubwayStationsAway.resolve(SubwayArrivalCode.ENROUTE, "5분 30초 후")).isNull();
    }

    @Test
    @DisplayName("조회역을 이미 출발했거나 모르는 코드면 거리를 지어내지 않는다")
    void doesNotInventDistance() {
        assertThat(SubwayStationsAway.resolve(SubwayArrivalCode.DEPARTED, "[1]번째 전역")).isNull();
        assertThat(SubwayStationsAway.resolve(SubwayArrivalCode.UNKNOWN, "[1]번째 전역")).isNull();
        assertThat(SubwayStationsAway.resolve(null, "[1]번째 전역")).isNull();
    }
}
