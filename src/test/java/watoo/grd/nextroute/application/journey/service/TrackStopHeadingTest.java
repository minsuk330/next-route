package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.TrackViews;
import watoo.grd.nextroute.application.journey.service.TrackStopHeading.BoardPatterns;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.PatternStop;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPattern;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitRoute;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import watoo.grd.nextroute.application.route.routing.RoutingMode;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("근처 정류장 진행 방향(#538)")
class TrackStopHeadingTest {

    private static final String SEB = "1:BS_TAGO_SEB121000091";
    private static final String GGB = "1:BS_TAGO_GGB121000091";
    private static final TransitStop ORIGIN = new TransitStop(SEB, "강남역9번출구", 37.5, 127.0, RoutingMode.BUS);
    private static final TransitStop ORIGIN_GGB = new TransitStop(GGB, "강남역.강남역사거리", 37.5, 127.0, RoutingMode.BUS);

    // 원점에서 약 90m 떨어진 정류장들
    private static final PatternStop EAST = new PatternStop("1:BS_TAGO_SEB1", "역삼역", 37.5, 127.001);
    private static final PatternStop EAST_NORTH = new PatternStop("1:BS_TAGO_SEB2", "역삼역북", 37.5004, 127.001);
    private static final PatternStop EAST_FAR = new PatternStop("1:BS_TAGO_SEB3", "선릉역", 37.5, 127.002);
    private static final PatternStop WEST = new PatternStop("1:BS_TAGO_SEB4", "교대역", 37.5, 126.999);
    private static final PatternStop NORTH = new PatternStop("1:BS_TAGO_SEB5", "신논현역", 37.501, 127.0);

    @Test
    @DisplayName("다음 정류장이 달라도 같은 쪽(±60°)이면 한 방향으로 센다")
    void bearingMajority() {
        Optional<TrackViews.Heading> heading = TrackStopHeading.of(ORIGIN, List.of(new BoardPatterns(ORIGIN, List.of(
                pattern("1:P146::01", "146", EAST, EAST_FAR),
                pattern("1:P740::01", "740", EAST, EAST_FAR),
                pattern("1:P2000::01", "2000", EAST_NORTH, EAST_FAR),
                pattern("1:P100::01", "100", WEST)))));

        assertThat(heading).isPresent();
        assertThat(heading.get().patternCodes()).containsExactly("1:P146::01", "1:P2000::01", "1:P740::01");
        // 대표 방향 안 다음 정류장 다수결(역삼역 2 : 역삼역북 1), 그 안 노선 이름 사전순 첫 분기(146)
        assertThat(heading.get().nextStopNames()).containsExactly("역삼역", "선릉역");
        assertThat(heading.get().bearingDegrees()).isCloseTo(90.0, within(0.5));
    }

    @Test
    @DisplayName("동률이면 노선 이름 사전순 첫 분기의 방향")
    void tieByRouteName() {
        Optional<TrackViews.Heading> heading = TrackStopHeading.of(ORIGIN, List.of(new BoardPatterns(ORIGIN, List.of(
                pattern("1:P200::01", "200", EAST),
                pattern("1:P100::01", "100", WEST)))));

        assertThat(heading.get().patternCodes()).containsExactly("1:P100::01");
        assertThat(heading.get().nextStopNames()).containsExactly("교대역");
        assertThat(heading.get().bearingDegrees()).isCloseTo(270.0, within(0.5));
    }

    @Test
    @DisplayName("형제 정류장(피드별 같은 정류장) 분기를 합쳐 센다")
    void siblingsSummed() {
        Optional<TrackViews.Heading> heading = TrackStopHeading.of(ORIGIN, List.of(
                new BoardPatterns(ORIGIN, List.of(pattern("1:P100::01", "100", WEST))),
                new BoardPatterns(ORIGIN_GGB, List.of(
                        patternOf("1:G1700::01", "1700", List.of(ggbStop(), EAST)),
                        patternOf("1:G7007::01", "7007", List.of(ggbStop(), EAST))))));

        assertThat(heading.get().patternCodes()).containsExactly("1:G1700::01", "1:G7007::01");
    }

    @Test
    @DisplayName("회차 정류장(양방향 패턴 공존): 많은 쪽이 대표, 꺾는 노선(90°)은 빠진다")
    void turnaroundMajority() {
        Optional<TrackViews.Heading> heading = TrackStopHeading.of(ORIGIN, List.of(new BoardPatterns(ORIGIN, List.of(
                // 동쪽에서 들어와 서쪽으로 돌아 나가는 노선: 승차 뒤는 서쪽뿐
                patternOf("1:A::01", "1", List.of(EAST_FAR, EAST, originStop(), WEST)),
                pattern("1:B::01", "2", EAST, EAST_FAR),
                pattern("1:C::01", "3", EAST, EAST_FAR),
                pattern("1:D::01", "4", NORTH)))));

        assertThat(heading.get().patternCodes()).containsExactly("1:B::01", "1:C::01");
    }

    @Test
    @DisplayName("왕복 패턴: 길 건너편 두 정류장에 같은 코드가 나와도 방위각은 반대다")
    void roundTripPatternAcrossStreet() {
        PatternStop sideA = new PatternStop("1:BS_TAGO_SEB10", "고속터미널", 37.5, 127.0);
        PatternStop sideB = new PatternStop("1:BS_TAGO_SEB11", "고속터미널", 37.5001, 127.0);
        PatternStop eastNext = new PatternStop("1:BS_TAGO_SEB12", "반포역", 37.5, 127.001);
        PatternStop westNext = new PatternStop("1:BS_TAGO_SEB13", "흑석역", 37.5001, 126.999);
        TransitPattern roundTrip = patternOf("1:P142::01", "142", List.of(sideA, eastNext, westNext, sideB, westNext));
        TransitStop a = new TransitStop(sideA.gtfsId(), "고속터미널", 37.5, 127.0, RoutingMode.BUS);
        TransitStop b = new TransitStop(sideB.gtfsId(), "고속터미널", 37.5001, 127.0, RoutingMode.BUS);

        TrackViews.Heading atA = TrackStopHeading.of(a, List.of(new BoardPatterns(a, List.of(roundTrip)))).orElseThrow();
        TrackViews.Heading atB = TrackStopHeading.of(b, List.of(new BoardPatterns(b, List.of(roundTrip)))).orElseThrow();

        assertThat(atA.patternCodes()).isEqualTo(atB.patternCodes());
        assertThat(TrackStopHeading.angleBetween(atA.bearingDegrees(), atB.bearingDegrees())).isGreaterThan(150);
    }

    @Test
    @DisplayName("패턴이 없거나 지원 안 되는 수단뿐이면 empty")
    void none() {
        assertThat(TrackStopHeading.of(ORIGIN, List.of(new BoardPatterns(ORIGIN, List.of())))).isEmpty();
        TransitPattern unsupported = new TransitPattern("1:X::01", null,
                new TransitRoute("1:X", "X", "-", RoutingMode.UNSUPPORTED, "FERRY", null, null),
                List.of(originStop(), EAST), 1);
        assertThat(TrackStopHeading.of(ORIGIN, List.of(new BoardPatterns(ORIGIN, List.of(unsupported))))).isEmpty();
    }

    @Test
    @DisplayName("좌표가 없으면 다음 정류장 key 다수결, 방위각 null")
    void withoutCoordinates() {
        PatternStop e = new PatternStop("1:BS_TAGO_SEB1", "역삼역");
        PatternStop w = new PatternStop("1:BS_TAGO_GGB4", "교대역");
        PatternStop wSibling = new PatternStop("1:BS_TAGO_SEB4", "교대역");

        TrackViews.Heading heading = TrackStopHeading.of(ORIGIN, List.of(new BoardPatterns(ORIGIN, List.of(
                pattern("1:P1::01", "1", e),
                pattern("1:P2::01", "2", w),
                pattern("1:P3::01", "3", wSibling))))).orElseThrow();

        // GGB4 와 SEB4 는 같은 정류장 key 라 2표
        assertThat(heading.patternCodes()).containsExactly("1:P2::01", "1:P3::01");
        assertThat(heading.bearingDegrees()).isNull();
    }

    @Test
    @DisplayName("다음 정류장 이름: 앞 1~2개, 같은 이름이면 하나")
    void nextStopNames() {
        assertThat(TrackStopHeading.nextStopNames(List.of(EAST))).containsExactly("역삼역");
        assertThat(TrackStopHeading.nextStopNames(List.of(EAST, EAST_FAR, WEST))).containsExactly("역삼역", "선릉역");
        assertThat(TrackStopHeading.nextStopNames(List.of(EAST,
                new PatternStop("1:BS_TAGO_SEB9", "역삼역", 37.5, 127.0011)))).containsExactly("역삼역");
        assertThat(TrackStopHeading.nextStopNames(List.of())).isEmpty();
    }

    @Test
    @DisplayName("방위각: 북 0, 동 90, 시계방향. 끝점 좌표 없으면 null")
    void bearing() {
        assertThat(TrackStopHeading.bearing(37.5, 127.0, 37.501, 127.0)).isCloseTo(0.0, within(0.5));
        assertThat(TrackStopHeading.bearing(37.5, 127.0, 37.5, 127.001)).isCloseTo(90.0, within(0.5));
        assertThat(TrackStopHeading.bearing(37.5, 127.0, 37.499, 127.0)).isCloseTo(180.0, within(0.5));
        assertThat(TrackStopHeading.bearing(37.5, 127.0, null, 127.0)).isNull();
        assertThat(TrackStopHeading.angleBetween(350, 10)).isEqualTo(20);
    }

    private static PatternStop ggbStop() {
        return new PatternStop(GGB, "강남역.강남역사거리", 37.5, 127.0);
    }

    private static PatternStop originStop() {
        return new PatternStop(SEB, "강남역9번출구", 37.5, 127.0);
    }

    /** 원점에서 타는 패턴: 원점 → 나머지. */
    private static TransitPattern pattern(String code, String routeName, PatternStop... next) {
        List<PatternStop> stops = new java.util.ArrayList<>();
        if (next.length == 0 || !next[0].gtfsId().equals(SEB)) {
            stops.add(originStop());
        }
        stops.addAll(List.of(next));
        return patternOf(code, routeName, stops);
    }

    private static TransitPattern patternOf(String code, String routeName, List<PatternStop> stops) {
        String routeId = code.substring(0, code.indexOf("::"));
        return new TransitPattern(code, null,
                new TransitRoute(routeId, routeName, "-", RoutingMode.BUS, "BUS", null, null), stops, 1);
    }
}
