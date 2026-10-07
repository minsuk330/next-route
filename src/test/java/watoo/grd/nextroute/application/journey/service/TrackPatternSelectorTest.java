package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.PatternStop;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPattern;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitRoute;
import watoo.grd.nextroute.application.route.routing.RoutingMode;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정류장 패턴 → 방향·구간. 모양은 2026-10-07 실측을 줄인 것이다: 2호선은 한 패턴이 여러 바퀴, 회차·단축 패턴이 많고,
 * headsign 은 전부 null, TAGO 버스 방향은 종점 이름뿐.
 */
@DisplayName("단독 추적 패턴 선택")
class TrackPatternSelectorTest {

    private static final TransitRoute LINE2_INNER = new TransitRoute("1:L2I", "서울2호선",
            "서울2호선(본선)<내선>", RoutingMode.SUBWAY, "SUBWAY", "00A23F", "1:A1");
    private static final TransitRoute LINE2_OUTER = new TransitRoute("1:L2O", "서울2호선",
            "서울2호선(본선)<외선>", RoutingMode.SUBWAY, "SUBWAY", "00A23F", "1:A1");
    private static final TransitRoute BUS = new TransitRoute("1:BR_TAGO_DJB30300151", "301", "-",
            RoutingMode.BUS, "BUS", null, "1:A1");

    @Test
    @DisplayName("순환: 승차역이 다시 나오기 직전에서 자르고, 단축·여러 바퀴 패턴은 한 방향으로 합친다")
    void circularCollapsesToOneDirection() {
        // A B C D(=끝) 한 바퀴 → 다시 A … 두 바퀴 패턴
        TransitPattern twoLaps = pattern("1:L2I::12", LINE2_INNER, 1, "A", "B", "C", "D", "A", "B", "C", "D", "A");
        TransitPattern shortTurn = pattern("1:L2I::10", LINE2_INNER, 2, "D", "A", "B");
        TransitPattern outer = pattern("1:L2O::01", LINE2_OUTER, 5, "A", "D", "C", "B", "A");

        List<TrackPatternSelector.Branch> branches =
                TrackPatternSelector.branches("A", List.of(twoLaps, shortTurn, outer));

        assertThat(branches).hasSize(2);
        TrackPatternSelector.Branch inner = branches.stream()
                .filter(b -> b.pattern().route() == LINE2_INNER).findFirst().orElseThrow();
        assertThat(inner.remaining()).extracting(PatternStop::gtfsId).containsExactly("B", "C", "D");
        assertThat(inner.circular()).isTrue();
        assertThat(inner.directionLabel()).isEqualTo("내선");
        TrackPatternSelector.Branch outerBranch = branches.stream()
                .filter(b -> b.pattern().route() == LINE2_OUTER).findFirst().orElseThrow();
        assertThat(outerBranch.directionLabel()).isEqualTo("외선");
    }

    @Test
    @DisplayName("갈래가 다르면(분기) 따로 남고, 방향 이름은 종점 방면이다")
    void branchesStaySeparate() {
        TransitPattern toX = pattern("1:B::01", BUS, 10, "A", "B", "X1", "X2");
        TransitPattern toY = pattern("1:B::02", BUS, 10, "A", "B", "Y1");
        TransitPattern shortX = pattern("1:B::03", BUS, 3, "A", "B", "X1");

        List<TrackPatternSelector.Branch> branches = TrackPatternSelector.branches("A", List.of(toX, toY, shortX));

        assertThat(branches).extracting(TrackPatternSelector.Branch::directionLabel)
                .containsExactly("X2 방면", "Y1 방면");
    }

    @Test
    @DisplayName("승차 정류장이 끝이면 그 패턴은 방향이 아니다")
    void terminalBoardHasNoDirection() {
        TransitPattern endsHere = pattern("1:B::01", BUS, 1, "Z", "A");

        assertThat(TrackPatternSelector.branches("A", List.of(endsHere))).isEmpty();
    }

    @Test
    @DisplayName("구간 선택: 한 바퀴 안에서 하차를 찾고, 가장 짧은 구간 → trip 많은 패턴")
    void selectShortest() {
        TransitPattern twoLaps = pattern("1:L2I::12", LINE2_INNER, 1, "A", "B", "C", "D", "A", "B", "C");
        TransitPattern busier = pattern("1:L2I::01", LINE2_INNER, 5, "D", "A", "B", "C");

        TrackPatternSelector.Selection selection =
                TrackPatternSelector.select("1:L2I", "A", "C", null, List.of(twoLaps, busier)).orElseThrow();

        assertThat(selection.pattern().code()).isEqualTo("1:L2I::01");
        assertThat(selection.boardIndex()).isEqualTo(1);
        assertThat(selection.alightIndex()).isEqualTo(3);
    }

    @Test
    @DisplayName("구간 선택: 첫 등장 기준 — 두 번째 바퀴로 넘어가지 않는다")
    void selectStaysInFirstLap() {
        TransitPattern twoLaps = pattern("1:L2I::12", LINE2_INNER, 1, "A", "B", "C", "A", "B", "C");

        TrackPatternSelector.Selection selection =
                TrackPatternSelector.select("1:L2I", "A", "C", null, List.of(twoLaps)).orElseThrow();

        assertThat(selection.boardIndex()).isZero();
        assertThat(selection.alightIndex()).isEqualTo(2);
    }

    @Test
    @DisplayName("순서가 거꾸로면(하차가 승차 앞) 고르지 않는다")
    void reversedOrder() {
        TransitPattern p = pattern("1:B::01", BUS, 1, "A", "B", "C");

        assertThat(TrackPatternSelector.select("1:BR_TAGO_DJB30300151", "C", "A", null, List.of(p))).isEmpty();
    }

    @Test
    @DisplayName("한 바퀴를 넘어야 닿는 하차는 고르지 않는다")
    void beyondOneLap() {
        // 순환 A→B→A→C: C 는 A 가 다시 나온 뒤라 한 바퀴 밖
        TransitPattern p = pattern("1:L2I::12", LINE2_INNER, 1, "A", "B", "A", "C");

        assertThat(TrackPatternSelector.select("1:L2I", "A", "C", null, List.of(p))).isEmpty();
    }

    @Test
    @DisplayName("패턴 코드가 맞으면 그것, 낡았으면(없는 코드) 노선으로 다시 찾는다")
    void patternCodeHint() {
        TransitPattern shorter = pattern("1:B::01", BUS, 9, "A", "C");
        TransitPattern hinted = pattern("1:B::02", BUS, 1, "A", "B", "C");

        assertThat(TrackPatternSelector.select("1:BR_TAGO_DJB30300151", "A", "C", "1:B::02",
                List.of(shorter, hinted)).orElseThrow().pattern().code()).isEqualTo("1:B::02");
        assertThat(TrackPatternSelector.select("1:BR_TAGO_DJB30300151", "A", "C", "1:B::99",
                List.of(shorter, hinted)).orElseThrow().pattern().code()).isEqualTo("1:B::01");
    }

    @Test
    @DisplayName("다른 노선의 패턴은 섞지 않는다")
    void otherRouteIgnored() {
        TransitPattern p = pattern("1:L2O::01", LINE2_OUTER, 1, "A", "C");

        assertThat(TrackPatternSelector.select("1:L2I", "A", "C", null, List.of(p))).isEmpty();
    }

    @Test
    @DisplayName("headsign 이 있으면 그것을 방향 이름으로 쓴다")
    void headsignWins() {
        TransitPattern p = new TransitPattern("1:B::01", "시청", BUS, stops("A", "B"), 1);

        assertThat(TrackPatternSelector.branches("A", List.of(p)))
                .extracting(TrackPatternSelector.Branch::directionLabel).containsExactly("시청");
    }

    private static TransitPattern pattern(String code, TransitRoute route, int trips, String... ids) {
        return new TransitPattern(code, null, route, stops(ids), trips);
    }

    private static List<PatternStop> stops(String... ids) {
        return Arrays.stream(ids).map(id -> new PatternStop(id, id)).toList();
    }
}
