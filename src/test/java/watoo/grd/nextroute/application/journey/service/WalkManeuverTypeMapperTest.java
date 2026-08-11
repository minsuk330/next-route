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
 * <p>이 테스트가 매핑표의 유일한 근거다. 레포에 TMAP 실측 응답 fixture 가 없어서, 표를 코드에서
 * 조용히 바꾸면 아무도 모르게 앱 안내가 틀어진다.
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
            "200, DEPART",
            "201, ARRIVE"
    })
    @DisplayName("회전 코드 매핑표")
    void mapsTurnTypes(int turnType, ManeuverType expected) {
        assertThat(mapper.map("GP", turnType)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {125, 126, 127, 128, 129, 130, 131})
    @DisplayName("시설 진입 코드는 STRAIGHT 로 접는다 — 안내 문구가 정보를 들고 있다")
    void foldsFacilityCodesToStraight(int turnType) {
        assertThat(mapper.map("GP", turnType)).isEqualTo(ManeuverType.STRAIGHT);
    }

    @ParameterizedTest
    @ValueSource(ints = {211, 212, 9999, -1, 0})
    @DisplayName("표에 없는 코드는 UNKNOWN")
    void unmappedCodeIsUnknown(int turnType) {
        assertThat(mapper.map("GP", turnType)).isEqualTo(ManeuverType.UNKNOWN);
    }

    @Test
    @DisplayName("회전 코드가 없고 지점 종류도 단서가 아니면 UNKNOWN")
    void nullTurnTypeIsUnknown() {
        assertThat(mapper.map("GP", null)).isEqualTo(ManeuverType.UNKNOWN);
        assertThat(mapper.map(null, null)).isEqualTo(ManeuverType.UNKNOWN);
    }

    @Test
    @DisplayName("지점 종류가 없어도 회전 코드만으로 판정한다")
    void turnTypeAloneIsEnough() {
        assertThat(mapper.map(null, 12)).isEqualTo(ManeuverType.LEFT);
    }
}
