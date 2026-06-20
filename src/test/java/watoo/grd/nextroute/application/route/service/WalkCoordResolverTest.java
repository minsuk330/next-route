package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.dto.LaneResult;
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
                List.of(new LaneResult("2호선", null, 2, null, null, null)),
                Collections.emptyList(),
                startName, endName,
                sx, sy, ex, ey,
                null, null, null, null, null,
                null,
                startExitNo, startExitX, startExitY,
                endExitNo,   endExitX,   endExitY,
                null,
                null, null, null, null, null, null, null
        );
    }

    private SubPathResult bus(String startName, double sx, double sy,
                              String endName,   double ex, double ey) {
        return new SubPathResult(
                2, 600, 1500.0,
                List.of(new LaneResult("100번", "100", null, null, null, null)),
                Collections.emptyList(),
                startName, endName,
                sx, sy, ex, ey,
                null, null, null, null, null,
                null,
                null, null, null,
                null, null, null,
                null,
                null, null, null, null, null, null, null
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
}
