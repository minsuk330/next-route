package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.dto.LaneResult;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.service.WalkCoordResolver.Resolved;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WalkCoordResolverTest {

    WalkCoordResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new WalkCoordResolver();
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    /**
     * 지하철 subPath 빌더. 출구 정보 옵션 포함.
     */
    private SubPathResult subway(String startName, double sx, double sy,
                                 String endName,   double ex, double ey,
                                 String startExitNo, Double startExitX, Double startExitY,
                                 String endExitNo,   Double endExitX,   Double endExitY) {
        return new SubPathResult(
                1, 1200, 6000.0,
                List.of(new LaneResult("2호선", null, 2, null, null, null, null)),
                Collections.emptyList(),
                startName, endName,
                sx, sy, ex, ey,
                null, null, null, null, null,
                null,
                startExitNo, startExitX, startExitY,
                endExitNo,   endExitX,   endExitY,
                null,
                null, null, null, null, null, null, null, null
        );
    }

    private SubPathResult bus(String startName, double sx, double sy,
                              String endName,   double ex, double ey) {
        return new SubPathResult(
                2, 600, 1500.0,
                List.of(new LaneResult("100번", "100", null, null, null, null, null)),
                Collections.emptyList(),
                startName, endName,
                sx, sy, ex, ey,
                null, null, null, null, null,
                null,
                null, null, null,
                null, null, null,
                null,
                null, null, null, null, null, null, null, null
        );
    }

    // ── 시드 테스트 1~8 ──────────────────────────────────────────────────

    @Test
    void TC1_도보_다음이_지하철_출구_정보_있음() {
        // 다음 = 지하철, 출구 좌표/번호 모두 있음 → 출구 사용
        SubPathResult next = subway(
                "강남역", 127.0276, 37.4979, "교대역", 127.0145, 37.4920,
                "11", 127.0285, 37.4985, null, null, null);

        Resolved r = resolver.resolveEnd(next, 127.0, 37.5, "원래 도착");

        assertThat(r.x()).isEqualTo(127.0285);
        assertThat(r.y()).isEqualTo(37.4985);
        assertThat(r.exitNo()).isEqualTo("11");
        assertThat(r.name()).isEqualTo("강남역 11번 출구");
    }

    @Test
    void TC2_도보_다음이_지하철_출구_없음_startX_Y로_폴백() {
        SubPathResult next = subway(
                "강남역", 127.0276, 37.4979, "교대역", 127.0145, 37.4920,
                null, null, null, null, null, null);

        Resolved r = resolver.resolveEnd(next, 127.0, 37.5, "원래 도착");

        assertThat(r.x()).isEqualTo(127.0276);
        assertThat(r.y()).isEqualTo(37.4979);
        assertThat(r.exitNo()).isNull();
        assertThat(r.name()).isEqualTo("강남역");
    }

    @Test
    void TC3_지하철_다음이_도보_출구_정보_있음() {
        SubPathResult prev = subway(
                "강남역", 127.0276, 37.4979, "교대역", 127.0145, 37.4920,
                null, null, null, "4", 127.0150, 37.4925);

        Resolved r = resolver.resolveStart(prev, 127.0, 37.5, "원래 출발");

        assertThat(r.x()).isEqualTo(127.0150);
        assertThat(r.y()).isEqualTo(37.4925);
        assertThat(r.exitNo()).isEqualTo("4");
        assertThat(r.name()).isEqualTo("교대역 4번 출구");
    }

    @Test
    void TC4_지하철_다음이_도보_출구_X만_있고_Y_없음_폴백() {
        // endExitX는 있지만 endExitY가 null → 출구 채택 불가, end()로 폴백
        SubPathResult prev = subway(
                "강남역", 127.0276, 37.4979, "교대역", 127.0145, 37.4920,
                null, null, null, "4", 127.0150, null);

        Resolved r = resolver.resolveStart(prev, 127.0, 37.5, "원래 출발");

        assertThat(r.x()).isEqualTo(127.0145);
        assertThat(r.y()).isEqualTo(37.4920);
        assertThat(r.exitNo()).isNull();
        assertThat(r.name()).isEqualTo("교대역");
    }

    @Test
    void TC5_도보_다음이_버스_출구_개념_없음() {
        SubPathResult next = bus("출발정류장", 127.05, 37.51, "도착정류장", 127.10, 37.55);

        Resolved r = resolver.resolveEnd(next, 127.0, 37.5, "원래 도착");

        assertThat(r.x()).isEqualTo(127.05);
        assertThat(r.y()).isEqualTo(37.51);
        assertThat(r.exitNo()).isNull();
        assertThat(r.name()).isEqualTo("출발정류장");
    }

    @Test
    void TC6_혼합_시나리오_버스_도보_지하철_도보_도착() {
        // 중간 도보 1: 버스 끝 → 지하철 시작 출구
        SubPathResult prevBus = bus("버스출발", 127.05, 37.51, "버스도착", 127.10, 37.55);
        SubPathResult nextSubway = subway(
                "강남역", 127.0276, 37.4979, "교대역", 127.0145, 37.4920,
                "11", 127.0285, 37.4985, null, null, null);

        Resolved start = resolver.resolveStart(prevBus, 0, 0, null);
        Resolved end   = resolver.resolveEnd(nextSubway, 0, 0, null);

        assertThat(start.x()).isEqualTo(127.10);
        assertThat(start.name()).isEqualTo("버스도착");
        assertThat(end.x()).isEqualTo(127.0285);
        assertThat(end.exitNo()).isEqualTo("11");
        assertThat(end.name()).isEqualTo("강남역 11번 출구");
    }

    @Test
    void TC7_첫_도보_출발지에서_지하철_출구로() {
        // prev == null (첫 도보)
        SubPathResult nextSubway = subway(
                "강남역", 127.0276, 37.4979, "교대역", 127.0145, 37.4920,
                "11", 127.0285, 37.4985, null, null, null);

        Resolved start = resolver.resolveStart(null, 127.0, 37.5, "출발지");
        Resolved end   = resolver.resolveEnd(nextSubway, 0, 0, null);

        assertThat(start.x()).isEqualTo(127.0);
        assertThat(start.y()).isEqualTo(37.5);
        assertThat(start.exitNo()).isNull();
        assertThat(start.name()).isEqualTo("출발지");

        assertThat(end.x()).isEqualTo(127.0285);
        assertThat(end.name()).isEqualTo("강남역 11번 출구");
    }

    @Test
    void TC8_마지막_도보_지하철_출구에서_도착지로() {
        // next == null (마지막 도보)
        SubPathResult prevSubway = subway(
                "강남역", 127.0276, 37.4979, "교대역", 127.0145, 37.4920,
                null, null, null, "4", 127.0150, 37.4925);

        Resolved start = resolver.resolveStart(prevSubway, 0, 0, null);
        Resolved end   = resolver.resolveEnd(null, 127.5, 37.6, "도착지");

        assertThat(start.x()).isEqualTo(127.0150);
        assertThat(start.exitNo()).isEqualTo("4");
        assertThat(start.name()).isEqualTo("교대역 4번 출구");

        assertThat(end.x()).isEqualTo(127.5);
        assertThat(end.y()).isEqualTo(37.6);
        assertThat(end.exitNo()).isNull();
        assertThat(end.name()).isEqualTo("도착지");
    }

    // ── 추가 엣지 케이스 ─────────────────────────────────────────────────

    @Test
    void TC_출구명_없으면_역명만() {
        // 출구 좌표는 있지만 exitNo가 null인 경우
        SubPathResult next = subway(
                "강남역", 127.0276, 37.4979, "교대역", 127.0145, 37.4920,
                null, 127.0285, 37.4985, null, null, null);

        Resolved r = resolver.resolveEnd(next, 0, 0, null);

        assertThat(r.x()).isEqualTo(127.0285);
        assertThat(r.exitNo()).isNull();
        assertThat(r.name()).isEqualTo("강남역");
    }

    @Test
    void TC_요청_명칭_빈문자열_더미_폴백() {
        Resolved start = resolver.resolveStart(null, 127.0, 37.5, null);
        assertThat(start.name()).isEqualTo("출발");

        Resolved end = resolver.resolveEnd(null, 127.0, 37.5, "");
        assertThat(end.name()).isEqualTo("도착");
    }

    // ── 경로 전체 채우기(OTP, #468) ──────────────────────────────────────

    /** OTP 가 내는 도보 구간: 형상·거리는 있고 이름·좌표는 비어 있다. */
    private SubPathResult otpWalk() {
        return new SubPathResult(
                3, 5, 320.0,
                null, List.of(),
                null, null,
                null, null, null, null,
                null, null, null, null, null,
                List.of(new CoordPoint(127.0, 37.5), new CoordPoint(127.001, 37.501)),
                null, null, null, null, null, null,
                null,
                null, null, null, null, null, null, null, null
        );
    }

    private SubPathResult otpSubway(String startName, double sx, double sy,
                                    String endName, double ex, double ey) {
        return subway(startName, sx, sy, endName, ex, ey, null, null, null, null, null, null);
    }

    private static RouteSearchResult resultOf(SubPathResult... subPaths) {
        PathInfo info = new PathInfo(20, -1, 640, 0, "첫 역", "끝 역", null);
        return new RouteSearchResult(0, 0, 1, 0, 0,
                List.of(new PathResult("0", 1, info, List.of(subPaths), List.of())));
    }

    private static List<SubPathResult> subPathsOf(RouteSearchResult result) {
        return result.paths().get(0).subPaths();
    }

    @Test
    @DisplayName("장소 목적지: 첫 걷기 시작은 출발 이름·좌표, 마지막 걷기 끝은 목적지 이름·좌표, 역 옆은 역 이름·좌표")
    void fillWalkEndpoints_placeDestination() {
        SubPathResult subway = otpSubway("강남", 127.0276, 37.4979, "삼성", 127.0630, 37.5088);
        RouteSearchResult in = resultOf(otpWalk(), subway, otpWalk());

        RouteSearchResult out = resolver.fillWalkEndpoints(in,
                127.0270, 37.4985, "현재 위치", 127.0590, 37.5115, "코엑스");

        SubPathResult first = subPathsOf(out).get(0);
        assertThat(first.startName()).isEqualTo("현재 위치");
        assertThat(first.startX()).isEqualTo(127.0270);
        assertThat(first.startY()).isEqualTo(37.4985);
        assertThat(first.endName()).isEqualTo("강남");
        assertThat(first.endX()).isEqualTo(127.0276);
        assertThat(first.endY()).isEqualTo(37.4979);

        SubPathResult last = subPathsOf(out).get(2);
        assertThat(last.startName()).isEqualTo("삼성");
        assertThat(last.startX()).isEqualTo(127.0630);
        assertThat(last.startY()).isEqualTo(37.5088);
        assertThat(last.endName()).isEqualTo("코엑스");
        assertThat(last.endX()).isEqualTo(127.0590);
        assertThat(last.endY()).isEqualTo(37.5115);

        // 이름·좌표 말고는 손대지 않는다 — OTP 도보 형상·거리, 대중교통 구간, 경로 키·요약.
        assertThat(last.polyline()).isEqualTo(otpWalk().polyline());
        assertThat(last.distance()).isEqualTo(320.0);
        assertThat(last.startExitNo()).isNull();
        assertThat(last.endExitX()).isNull();
        assertThat(subPathsOf(out).get(1)).isSameAs(subway);
        assertThat(out.paths().get(0).pathKey()).isEqualTo("0");
        assertThat(out.paths().get(0).info()).isEqualTo(in.paths().get(0).info());
    }

    @Test
    @DisplayName("걷기 끝점 복사본도 trainKind 를 흘리지 않는다 — 지하철 구간은 그대로, 다시 만든 걷기 구간도 칸 보존(S2)")
    void fillWalkEndpoints_keepsTrainKind() {
        SubPathResult subway = watoo.grd.nextroute.application.journey.service.SubPathFixture.withTrainKind(
                otpSubway("가양", 126.8544, 37.5614, "염창", 126.8747, 37.5469), "EXPRESS");
        SubPathResult marked = watoo.grd.nextroute.application.journey.service.SubPathFixture.withTrainKind(
                otpWalk(), "MARK");

        RouteSearchResult out = resolver.fillWalkEndpoints(resultOf(marked, subway, otpWalk()),
                126.85, 37.56, "출발지", 126.88, 37.54, "도착지");

        assertThat(subPathsOf(out).get(0).startName()).isEqualTo("출발지");
        assertThat(subPathsOf(out).get(0).trainKind()).isEqualTo("MARK");
        assertThat(subPathsOf(out).get(1).trainKind()).isEqualTo("EXPRESS");
        assertThat(subPathsOf(out).get(2).trainKind()).isNull();
    }

    @Test
    @DisplayName("교통수단 사이 걷기: 앞 구간 하차 정류장 → 뒤 구간 승차역")
    void fillWalkEndpoints_walkBetweenTransit() {
        SubPathResult bus = bus("고속터미널", 127.004, 37.5058, "사당1동상점가", 126.978, 37.4768);
        SubPathResult subway = otpSubway("사당(4호선)", 126.9817, 37.4776, "숙대입구", 126.9721, 37.5446);

        RouteSearchResult out = resolver.fillWalkEndpoints(resultOf(bus, otpWalk(), subway),
                127.0, 37.5, "출발지", 126.97, 37.55, "도착지");

        SubPathResult transfer = subPathsOf(out).get(1);
        assertThat(transfer.startName()).isEqualTo("사당1동상점가");
        assertThat(transfer.startX()).isEqualTo(126.978);
        assertThat(transfer.startY()).isEqualTo(37.4768);
        assertThat(transfer.endName()).isEqualTo("사당(4호선)");
        assertThat(transfer.endX()).isEqualTo(126.9817);
        assertThat(transfer.endY()).isEqualTo(37.4776);
    }

    @Test
    @DisplayName("역 목적지: 교통수단 구간에서 끝나면 마지막 구간은 그대로 — 끝 이름은 하차역")
    void fillWalkEndpoints_stationDestinationUnchanged() {
        SubPathResult subway = otpSubway("서울역", 126.9707, 37.5547, "시청", 126.9769, 37.5657);
        RouteSearchResult in = resultOf(otpWalk(), subway);

        RouteSearchResult out = resolver.fillWalkEndpoints(in,
                126.9700, 37.5550, "현재 위치", 126.9769, 37.5657, "시청역");

        assertThat(subPathsOf(out).get(1)).isSameAs(subway);
        assertThat(subPathsOf(out).get(1).endName()).isEqualTo("시청");
        assertThat(subPathsOf(out).get(0).startName()).isEqualTo("현재 위치");
        assertThat(subPathsOf(out).get(0).endName()).isEqualTo("서울역");
    }

    @Test
    @DisplayName("걷기 옆이 걷기면 그쪽 끝점은 비워 둔다 — 경로 중간을 출발지·목적지로 적지 않는다")
    void fillWalkEndpoints_walkNextToWalkKeepsUnknownSide() {
        SubPathResult bus = bus("정류장A", 127.01, 37.51, "정류장B", 127.02, 37.52);

        RouteSearchResult out = resolver.fillWalkEndpoints(resultOf(otpWalk(), otpWalk(), bus),
                127.0, 37.5, "집", 127.03, 37.53, "회사");

        SubPathResult first = subPathsOf(out).get(0);
        SubPathResult second = subPathsOf(out).get(1);
        assertThat(first.startName()).isEqualTo("집");
        assertThat(first.endName()).isNull();
        assertThat(first.endX()).isNull();
        assertThat(second.startName()).isNull();
        assertThat(second.startX()).isNull();
        assertThat(second.endName()).isEqualTo("정류장A");
    }

    @Test
    @DisplayName("경로가 없으면 받은 결과를 그대로 돌려준다")
    void fillWalkEndpoints_emptyResult() {
        RouteSearchResult empty = new RouteSearchResult(0, 0, 0, 0, 0, List.of());

        assertThat(resolver.fillWalkEndpoints(empty, 127.0, 37.5, "a", 127.1, 37.6, "b")).isSameAs(empty);
    }
}
