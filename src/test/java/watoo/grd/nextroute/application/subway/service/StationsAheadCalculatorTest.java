package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.service.StationsAheadCalculator.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시나리오는 운영 fixture({@link SubwayPatternFixtures}) 위에서 돈다 — 고른 가짜 패턴으로는 2호선 다회전,
 * 1호선 급행 통과 패턴, 경의중앙 반전 노선 같은 실제 모양이 안 나온다.
 */
class StationsAheadCalculatorTest {

    @Nested
    @DisplayName("2호선(순환)")
    class Line2 {

        @Test
        @DisplayName("강남 내선: 한 바퀴, 승차역 바로 앞(역삼)까지 — 패턴이 여러 바퀴여도 한 바퀴")
        void oneLapFromGangnam() {
            Result r = calc("1002", "내선", "강남", null);

            assertThat(r.endReason()).isEqualTo(EndReason.LOOP);
            assertThat(r.stations()).hasSize(42);
            assertThat(first(r)).isEqualTo("교대");
            assertThat(last(r)).isEqualTo("역삼");
            assertThat(r.stations()).extracting(Ahead::stopsAway).startsWith(1, 2, 3);
            assertThat(r.stations().get(0).minutes()).isEqualTo(2);
        }

        @Test
        @DisplayName("행선지 '성수'로 자르지 않는다 — 실시간 2호선 행선지는 거의 다 성수다")
        void neverCutsAtSeongsu() {
            Result r = calc("1002", "내선", "강남", "성수");

            assertThat(r.endReason()).isEqualTo(EndReason.LOOP);
            assertThat(r.stations()).hasSize(42);
        }

        @Test
        @DisplayName("신도림 내선: 승차역이 분기점 → 빈 목록 + 본선·신정지선 갈래")
        void sindorimIsABranchPoint() {
            Result r = calc("1002", "내선", "신도림", null);

            assertThat(r.endReason()).isEqualTo(EndReason.BRANCH);
            assertThat(r.stations()).isEmpty();
            assertThat(r.branches()).containsExactlyInAnyOrder(
                    new Branch("까치산", "도림천", EndReason.TERMINAL),
                    new Branch("순환", "문래", EndReason.LOOP));
        }

        @Test
        @DisplayName("#292 순환 본선 갈래는 '대림'(한 바퀴 끝 역)이 아니라 '순환' — '대림 방면'은 반대 방향으로 읽힌다")
        void loopBranchIsNamedLoopNotTheLastStation() {
            Result r = calc("1002", "내선", "신도림", null);
            Result again = calc("1002", "내선", "신도림", StationsAheadCalculator.LOOP_DESTINATION);

            assertThat(r.branches()).extracting(Branch::destinationName).doesNotContain("대림");
            assertThat(again.endReason()).isEqualTo(EndReason.LOOP);
            assertThat(first(again)).isEqualTo("문래");
            assertThat(again.stations()).hasSize(42);
        }

        @Test
        @DisplayName("성수 내선도 같은 모양 — 본선 '순환' + 성수지선 '신설동'")
        void seongsuInnerBranches() {
            Result r = calc("1002", "내선", "성수", null);

            assertThat(r.stations()).isEmpty();
            assertThat(r.branches()).extracting(Branch::destinationName, Branch::kind).containsExactlyInAnyOrder(
                    org.assertj.core.groups.Tuple.tuple("순환", EndReason.LOOP),
                    org.assertj.core.groups.Tuple.tuple("신설동", EndReason.TERMINAL));
        }

        @Test
        @DisplayName("신도림 + 까치산 → 신정지선 끝까지, 신도림 + 성수 → 본선 한 바퀴")
        void destinationPicksBranchOrMainLine() {
            Result branch = calc("1002", "내선", "신도림", "까치산");
            Result main = calc("1002", "내선", "신도림", "성수");

            assertThat(branch.endReason()).isEqualTo(EndReason.TERMINAL);
            assertThat(names(branch)).containsExactly("도림천", "양천구청", "신정네거리", "까치산");
            assertThat(branch.destinationApplied()).isTrue();
            assertThat(main.endReason()).isEqualTo(EndReason.LOOP);
            assertThat(main.stations()).hasSize(42);
        }

        @Test
        @DisplayName("갈래의 destinationName 을 다시 보내면 그 갈래가 나온다")
        void branchDestinationRoundTrips() {
            for (Branch b : calc("1002", "내선", "신도림", null).branches()) {
                Result again = calc("1002", "내선", "신도림", b.destinationName());
                assertThat(first(again)).as(b.toString()).isEqualTo(b.nextStationName());
            }
        }
    }

    @Nested
    @DisplayName("분기 노선")
    class Branches {

        @Test
        @DisplayName("5호선 방화 하행: 강동까지(분기점), 갈래 = 마천·하남검단산")
        void line5StopsAtGangdong() {
            Result r = calc("1005", "하행", "방화", null);

            assertThat(r.endReason()).isEqualTo(EndReason.BRANCH);
            assertThat(last(r)).isEqualTo("강동");
            assertThat(r.branches()).extracting(Branch::destinationName).containsExactlyInAnyOrder("마천", "하남검단산");
        }

        @Test
        @DisplayName("5호선 행선지 마천 → 마천까지(포함)")
        void line5DestinationCutsAtTerminal() {
            Result r = calc("1005", "하행", "방화", "마천");

            assertThat(r.endReason()).isEqualTo(EndReason.TERMINAL);
            assertThat(r.destinationApplied()).isTrue();
            assertThat(last(r)).isEqualTo("마천");
            assertThat(names(r)).doesNotContain("길동");
        }

        @Test
        @DisplayName("강동 하행 승차: 빈 목록 + 갈래")
        void boardingAtBranchPoint() {
            Result r = calc("1005", "하행", "강동", null);

            assertThat(r.stations()).isEmpty();
            assertThat(r.branches()).extracting(Branch::nextStationName).containsExactlyInAnyOrder("길동", "둔촌동");
        }

        @Test
        @DisplayName("1호선 의정부 하행: 급행 통과 패턴에 깎이지 않고 구로까지 — 회룡 1역에서 멈추지 않는다")
        void line1ExpressDoesNotShrinkCommonPart() {
            Result r = calc("1001", "하행", "의정부", null);

            assertThat(r.endReason()).isEqualTo(EndReason.BRANCH);
            assertThat(names(r)).contains("망월사", "도봉산", "도봉", "방학", "녹천", "월계");
            assertThat(last(r)).isEqualTo("구로");
        }

        @Test
        @DisplayName("경의중앙선 가좌 하행: 반전 노선이 섞여도 문산 쪽 역이 안 나온다, 서울행은 서울까지")
        void gyeonguiReversedRouteStaysInDirection() {
            Result none = calc("1063", "하행", "가좌", null);
            Result seoul = calc("1063", "하행", "가좌", "서울");

            assertThat(none.endReason()).isEqualTo(EndReason.BRANCH);
            assertThat(none.branches()).extracting(Branch::destinationName).doesNotContain("문산");
            assertThat(names(seoul)).last().isEqualTo("서울");
        }

        @Test
        @DisplayName("행선지가 어느 갈래에도 없으면 공통 구간으로 떨어진다")
        void unknownDestinationFallsBack() {
            Result r = calc("1005", "하행", "방화", "어딘가");

            assertThat(r.destinationApplied()).isFalse();
            assertThat(last(r)).isEqualTo("강동");
        }
    }

    @Nested
    @DisplayName("방향 보정된 노선")
    class Corrected {

        @Test
        @DisplayName("9호선 김포공항 상행: 일반 열차 정차역으로 중앙보훈병원까지")
        void line9Local() {
            Result r = calc("1009", "상행", "김포공항", null);

            assertThat(r.endReason()).isEqualTo(EndReason.TERMINAL);
            assertThat(first(r)).isEqualTo("공항시장");
            assertThat(last(r)).isEqualTo("중앙보훈병원");
        }

        @Test
        @DisplayName("6호선 공덕 상행 → 응암까지, 역촌 하행 → 순환 돌아 신내까지")
        void line6Split() {
            Result up = calc("1006", "상행", "공덕", null);
            Result down = calc("1006", "하행", "역촌", null);

            assertThat(last(up)).startsWith("응암");
            assertThat(first(down)).isEqualTo("불광");
            assertThat(names(down)).contains("구산", "새절(신사)");
            assertThat(last(down)).isEqualTo("신내");
        }

        @Test
        @DisplayName("공항철도 서울 상행 → 인천공항2터미널까지(검암행 짧은 패턴에 안 깎인다)")
        void airportRailroad() {
            assertThat(last(calc("1065", "상행", "서울", null))).isEqualTo("인천공항2터미널");
        }
    }

    @Nested
    @DisplayName("규칙 단위")
    class Rules {

        @Test
        @DisplayName("역에 못 이은 정차도 빼지 않는다 — 빼면 남은 정거장 수가 틀린다")
        void keepsUnmatchedStops() {
            Pattern p = new Pattern("R", "노선<하행>", false, 10, List.of(
                    stop("A", 0, 0), new Stop("name:진접", null, "진접", 100, 100), stop("C", 200, 200)));

            Result r = StationsAheadCalculator.calculate(List.of(p), "A", null);

            assertThat(r.stations()).extracting(a -> a.stop().name()).containsExactly("진접", "C");
            assertThat(r.stations().get(0).stop().stationId()).isNull();
            assertThat(r.stations().get(1).stopsAway()).isEqualTo(2);
        }

        @Test
        @DisplayName("시각이 거꾸로면 분을 비운다 — 지어내지 않는다")
        void negativeMinutesBecomeNull() {
            Pattern p = new Pattern("R", "노선<하행>", false, 10, List.of(stop("A", 500, 500), stop("B", 100, 100)));

            assertThat(StationsAheadCalculator.calculate(List.of(p), "A", null).stations().get(0).minutes()).isNull();
        }

        @Test
        @DisplayName("승차역이 끝역이면 빈 목록 TERMINAL")
        void boardingAtTerminal() {
            Pattern p = new Pattern("R", "노선<하행>", false, 10, List.of(stop("A", 0, 0), stop("B", 60, 60)));

            Result r = StationsAheadCalculator.calculate(List.of(p), "B", null);

            assertThat(r.stations()).isEmpty();
            assertThat(r.endReason()).isEqualTo(EndReason.TERMINAL);
            assertThat(StationsAheadCalculator.boardsAnywhere(List.of(p), "B")).isTrue();
            assertThat(StationsAheadCalculator.boardsAnywhere(List.of(p), "Z")).isFalse();
        }

        private Stop stop(String id, int arr, int dep) {
            return new Stop(id, id, id, arr, dep);
        }
    }

    @Nested
    @DisplayName("#317 오는 쪽 역(behind)")
    class BehindStations {

        @Test
        @DisplayName("2호선 강남 내선: 먼 역 → 가까운 역 4개, 초는 GTFS 계획 그대로")
        void loopLineFourStations() {
            Behind b = behind("1002", "내선", "강남");

            assertThat(b.end()).isEqualTo(BehindEnd.FULL);
            assertThat(names(b)).containsExactly("종합운동장", "삼성", "선릉", "역삼");
            assertThat(b.secondsToHere()).containsExactly(450, 330, 180, 60);
            assertThat(b.runSeconds()).containsExactly(90, 90, 90, 90);
            assertThat(b.boardDwellSeconds()).isEqualTo(30);
        }

        @Test
        @DisplayName("1호선 수원 하행: 급행 통과 패턴이 거짓 갈래를 만들지 않는다")
        void expressPatternDoesNotFakeABranch() {
            Behind b = behind("1001", "하행", "수원");

            assertThat(b.end()).isEqualTo(BehindEnd.FULL);
            assertThat(names(b)).containsExactly("당정", "의왕", "성균관대", "화서");
        }

        @Test
        @DisplayName("1호선 가산디지털단지 상행: 금천구청 앞은 갈래(광명)라 거기서 끊는다")
        void cutsAtMergePoint() {
            Behind b = behind("1001", "상행", "가산디지털단지");

            assertThat(b.end()).isEqualTo(BehindEnd.BRANCH);
            assertThat(names(b)).containsExactly("금천구청", "독산");
        }

        @Test
        @DisplayName("기점이면 짧거나 비고 ORIGIN — 9호선 김포공항 상행은 개화 하나, 5호선 방화 하행은 없음")
        void originIsShort() {
            Behind gimpo = behind("1009", "상행", "김포공항");
            assertThat(gimpo.end()).isEqualTo(BehindEnd.ORIGIN);
            assertThat(names(gimpo)).containsExactly("개화");
            assertThat(gimpo.runSeconds()).containsExactly((Integer) null); // 개화 앞이 없다

            Behind banghwa = behind("1005", "하행", "방화");
            assertThat(banghwa.end()).isEqualTo(BehindEnd.ORIGIN);
            assertThat(banghwa.stops()).isEmpty();
        }

        @Test
        @DisplayName("9호선(방향 보정 노선) 신논현 하행: 오는 쪽은 언주 쪽, 다음 역은 사평")
        void correctedDirectionLine() {
            Behind b = behind("1009", "하행", "신논현");

            assertThat(names(b)).endsWith("선정릉", "언주");
            assertThat(calc("1009", "하행", "신논현", null).stations().get(0).stop().name()).isEqualTo("사평");
        }

        @Test
        @DisplayName("패턴에 없는 역은 UNKNOWN")
        void unknownStation() {
            assertThat(StationsAheadCalculator.behind(SubwayPatternFixtures.patterns("1002", "내선"), "nope", 4).end())
                    .isEqualTo(BehindEnd.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("#318 남은 역 초")
    class AheadSeconds {

        @Test
        @DisplayName("다음 역 초 = 승차역 출발 → 도착, 정차 초도 싣는다")
        void firstStationSeconds() {
            Ahead next = calc("1001", "하행", "수원", null).stations().get(0);

            assertThat(next.stop().name()).isEqualTo("세류");
            assertThat(next.secondsFromBoard()).isEqualTo(180);
            assertThat(next.runSeconds()).isEqualTo(180);
            assertThat(next.dwellSeconds()).isEqualTo(30);
        }

        @Test
        @DisplayName("둘째 역부터 runSeconds 는 직전 역 출발 기준, secondsFromBoard 는 누적")
        void runSecondsAreFromPreviousStop() {
            List<Ahead> stations = calc("1002", "내선", "강남", null).stations();

            Ahead second = stations.get(1);
            assertThat(second.secondsFromBoard()).isGreaterThan(stations.get(0).secondsFromBoard());
            assertThat(second.runSeconds()).isEqualTo(second.secondsFromBoard()
                    - stations.get(0).secondsFromBoard() - stations.get(0).dwellSeconds());
        }

        @Test
        @DisplayName("시각이 거꾸로거나 없으면 초를 비운다")
        void reversedTimesAreNull() {
            assertThat(StationsAheadCalculator.seconds(100, 50)).isNull();
            assertThat(StationsAheadCalculator.seconds(null, 50)).isNull();
            assertThat(StationsAheadCalculator.seconds(50, 50)).isZero();
        }
    }

    private static Behind behind(String lineId, String direction, String board) {
        return StationsAheadCalculator.behind(SubwayPatternFixtures.patterns(lineId, direction),
                SubwayPatternFixtures.stationId(lineId, board), 4);
    }

    private static List<String> names(Behind b) {
        return b.stops().stream().map(Stop::name).toList();
    }

    private static Result calc(String lineId, String direction, String board, String destination) {
        return StationsAheadCalculator.calculate(SubwayPatternFixtures.patterns(lineId, direction),
                SubwayPatternFixtures.stationId(lineId, board), destination);
    }

    private static List<String> names(Result r) {
        return r.stations().stream().map(a -> a.stop().name()).toList();
    }

    private static String first(Result r) {
        return r.stations().get(0).stop().name();
    }

    private static String last(Result r) {
        return r.stations().get(r.stations().size() - 1).stop().name();
    }
}
