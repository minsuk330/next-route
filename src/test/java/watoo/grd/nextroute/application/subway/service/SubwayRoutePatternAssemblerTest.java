package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.dto.DirectedRailPattern;
import watoo.grd.nextroute.application.subway.dto.OtpRailRoute;
import watoo.grd.nextroute.application.subway.dto.RailPattern;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubwayRoutePatternAssemblerTest {

    private static final OtpRailRoute LINE2_MAIN_INNER = route("1:RR_ACC1_S-1-02-1I", "서울2호선", "서울2호선(본선)<내선>");
    private static final OtpRailRoute LINE2_SEONGSU_INNER = route("1:RR_ACC1_S-1-02-2I", "서울2호선", "서울2호선(성수지선)<내선>");
    private static final OtpRailRoute LINE2_SEONGSU_OUTER = route("1:RR_ACC1_S-1-02-2O", "서울2호선", "서울2호선(성수지선)<외선>");
    private static final OtpRailRoute INCHEON2_UP = route("1:RR_ACC1_S-1-I2-1U", "인천2호선", "인천2호선<상행>");
    private static final OtpRailRoute LINE9_LOCAL_DOWN = route("1:RR_ACC1_S-1-09-1D", "서울9호선", "서울9호선(일반)<하행>");
    private static final OtpRailRoute LINE9_EXPRESS_UP = route("1:RR_ACC1_S-1-09-2U", "서울9호선", "서울9호선(급행)<상행>");
    private static final OtpRailRoute LINE6 = route("1:RR_ACC1_S-1-06-00", "서울6호선", "서울6호선");

    @Test
    @DisplayName("shortName 명시표로 노선을 고른다 — 인천2호선은 2호선이 아니다")
    void picksByExplicitShortName() {
        assertThat(SubwayRoutePatternAssembler.selectRoutes(
                List.of(LINE2_MAIN_INNER, INCHEON2_UP), "1002", "내선"))
                .containsExactly(LINE2_MAIN_INNER);
    }

    @Test
    @DisplayName("라벨이 실시간과 반대인 노선은 뒤집어 고른다 — 성수지선 OTP 외선 = 실시간 내선")
    void reversesKnownRoutes() {
        List<OtpRailRoute> all = List.of(LINE2_MAIN_INNER, LINE2_SEONGSU_INNER, LINE2_SEONGSU_OUTER);

        assertThat(SubwayRoutePatternAssembler.selectRoutes(all, "1002", "내선"))
                .containsExactly(LINE2_MAIN_INNER, LINE2_SEONGSU_OUTER);
    }

    @Test
    @DisplayName("9호선: OTP 하행(개화→중앙보훈병원)이 실시간 상행이다, 급행 노선은 뺀다")
    void line9IsReversedAndExpressIsDropped() {
        List<OtpRailRoute> all = List.of(LINE9_LOCAL_DOWN, LINE9_EXPRESS_UP);

        assertThat(SubwayRoutePatternAssembler.selectRoutes(all, "1009", "상행")).containsExactly(LINE9_LOCAL_DOWN);
        assertThat(SubwayRoutePatternAssembler.selectRoutes(all, "1009", "하행")).isEmpty();
    }

    @Test
    @DisplayName("6호선은 응암 첫 등장에서 가른다 — 응암까지가 상행, 응암부터 순환을 돌아 나오는 쪽이 하행")
    void splitsLine6AtEungam() {
        RailPattern roundTrip = pattern(LINE6, 62, "봉화산", "새절", "응암", "역촌", "구산", "응암", "새절", "봉화산");

        List<DirectedRailPattern> up = SubwayRoutePatternAssembler.orient(LINE6, List.of(roundTrip), "상행");
        List<DirectedRailPattern> down = SubwayRoutePatternAssembler.orient(LINE6, List.of(roundTrip), "하행");

        assertThat(names(up.get(0))).containsExactly("봉화산", "새절", "응암");
        assertThat(names(down.get(0))).containsExactly("응암", "역촌", "구산", "응암", "새절", "봉화산");
        assertThat(up.get(0).loop()).isFalse();
        assertThat(SubwayRoutePatternAssembler.selectRoutes(List.of(LINE6), "1006", "상행")).containsExactly(LINE6);
    }

    @Test
    @DisplayName("6호선 패턴에 응암이 없으면 방향을 몰라 뺀다")
    void dropsLine6PatternWithoutPivot() {
        RailPattern shortTurn = pattern(LINE6, 1, "봉화산", "새절");

        assertThat(SubwayRoutePatternAssembler.orient(LINE6, List.of(shortTurn), "상행")).isEmpty();
    }

    @Test
    @DisplayName("내선·외선 패턴은 순환으로 표시한다")
    void marksLoopDirections() {
        RailPattern lap = pattern(LINE2_MAIN_INNER, 5, "성수", "건대입구", "성수");

        assertThat(SubwayRoutePatternAssembler.orient(LINE2_MAIN_INNER, List.of(lap), "내선").get(0).loop()).isTrue();
    }

    @Test
    void knowsTheRealtimeDirectionSpace() {
        assertThat(List.of("상행", "하행", "내선", "외선")).allMatch(SubwayRoutePatternAssembler::isKnownDirection);
        assertThat(SubwayRoutePatternAssembler.isKnownDirection("up")).isFalse();
        assertThat(SubwayRoutePatternAssembler.isKnownDirection(null)).isFalse();
    }

    private static OtpRailRoute route(String id, String shortName, String longName) {
        return new OtpRailRoute(id, shortName, longName);
    }

    private static RailPattern pattern(OtpRailRoute route, int trips, String... names) {
        return new RailPattern(route.gtfsId(), trips, java.util.Arrays.stream(names)
                .map(n -> new RailPattern.Stop(n, 0, 0, null, null)).toList());
    }

    private static List<String> names(DirectedRailPattern pattern) {
        return pattern.stops().stream().map(RailPattern.Stop::name).toList();
    }
}
