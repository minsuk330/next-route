package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import watoo.grd.nextroute.application.journey.dto.ManeuverType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TMAP 회전 코드 → 계약 타입 매핑을 고정한다.
 *
 * <p>표의 출처는 TMAP 공식 문서 「경로안내 샘플예제」의 <b>보행자</b> 응답 스펙이다. 자동차 표와
 * 코드 뜻이 달라서(130·131 은 자동차 전용) 옮겨 쓰면 틀린 방향을 말하게 된다. 코드에서 표를 조용히
 * 바꾸면 앱 안내가 틀어지므로 여기서 값을 하나씩 못박는다.
 */
class WalkManeuverTypeMapperTest {

    private final WalkManeuverTypeMapper mapper = new WalkManeuverTypeMapper();

    @Test
    @DisplayName("출발점은 회전 코드가 무엇이든 DEPART")
    void departWinsOverTurnType() {
        assertThat(mapper.map("SP", 200)).isEqualTo(ManeuverType.DEPART);
        assertThat(mapper.map("SP", null)).isEqualTo(ManeuverType.DEPART);
        assertThat(mapper.map("SP", 12)).isEqualTo(ManeuverType.DEPART);
    }

    @Test
    @DisplayName("도착점은 회전 코드가 무엇이든 ARRIVE")
    void arriveWinsOverTurnType() {
        assertThat(mapper.map("EP", 201)).isEqualTo(ManeuverType.ARRIVE);
        assertThat(mapper.map("EP", null)).isEqualTo(ManeuverType.ARRIVE);
        assertThat(mapper.map("EP", 13)).isEqualTo(ManeuverType.ARRIVE);
    }

    @ParameterizedTest
    @CsvSource({
            "11, STRAIGHT",
            "12, LEFT",
            "13, RIGHT",
            "14, UTURN",
            "16, SHARP_LEFT",
            "17, SLIGHT_LEFT",
            "18, SLIGHT_RIGHT",
            "19, SHARP_RIGHT",
            "184, STRAIGHT",
            "185, STRAIGHT",
            "186, STRAIGHT",
            "187, STRAIGHT",
            "188, STRAIGHT",
            "189, STRAIGHT",
            "200, DEPART",
            "201, ARRIVE",
            "233, STRAIGHT"
    })
    @DisplayName("회전 코드 매핑표")
    void mapsTurnTypes(int turnType, ManeuverType expected) {
        assertThat(mapper.map("GP", turnType)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
    @DisplayName("안내 없음(1~7)은 STRAIGHT — 회전이 아니다")
    void foldsNoGuidanceCodesToStraight(int turnType) {
        assertThat(mapper.map("GP", turnType)).isEqualTo(ManeuverType.STRAIGHT);
    }

    @ParameterizedTest
    @ValueSource(ints = {125, 126, 127, 128, 129, 218})
    @DisplayName("방향 없는 시설(육교·지하보도·계단·경사로·엘리베이터)은 STRAIGHT 로 접는다")
    void foldsFacilityCodesToStraight(int turnType) {
        assertThat(mapper.map("GP", turnType)).isEqualTo(ManeuverType.STRAIGHT);
    }

    @ParameterizedTest
    @ValueSource(ints = {211, 212, 213, 214, 215, 216, 217})
    @DisplayName("횡단보도 계열은 CROSSWALK — 건너는 것을 직진이라 부르지 않는다")
    void crosswalkCodesAreCrosswalk(int turnType) {
        assertThat(mapper.map("GP", turnType)).isEqualTo(ManeuverType.CROSSWALK);
    }

    @ParameterizedTest
    @ValueSource(ints = {130, 131, 9999, -1, 0})
    @DisplayName("보행자 표에 없는 코드는 UNKNOWN — 130·131 은 자동차 전용이라 옮겨 쓰지 않는다")
    void unmappedCodeIsUnknown(int turnType) {
        assertThat(mapper.map("GP", turnType)).isEqualTo(ManeuverType.UNKNOWN);
    }

    @Test
    @DisplayName("회전 코드가 없고 지점 종류도 단서가 아니면 UNKNOWN")
    void nullTurnTypeIsUnknown() {
        assertThat(mapper.map("GP", null)).isEqualTo(ManeuverType.UNKNOWN);
        assertThat(mapper.map(null, null)).isEqualTo(ManeuverType.UNKNOWN);
    }

    @ParameterizedTest
    @CsvSource({"PP, 184", "PP1, 185", "PP5, 189", "GP, 184"})
    @DisplayName("경유지 지점은 출발·도착이 아니라 회전 코드로 판정한다")
    void waypointPointTypesFallThroughToTurnType(String pointType, int turnType) {
        assertThat(mapper.map(pointType, turnType)).isEqualTo(ManeuverType.STRAIGHT);
    }

    @Test
    @DisplayName("지점 종류가 없어도 회전 코드만으로 판정한다")
    void turnTypeAloneIsEnough() {
        assertThat(mapper.map(null, 12)).isEqualTo(ManeuverType.LEFT);
    }
}
