package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.route.dto.*;
import watoo.grd.nextroute.application.route.exception.TmapApiException;
import watoo.grd.nextroute.application.route.port.out.TmapPedestrianPort;
import watoo.grd.nextroute.application.route.port.out.TmapPedestrianPort.WalkSearchCommand;
import watoo.grd.nextroute.application.route.port.out.WalkSegmentCachePort;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalkSegmentEnricherTest {

    @Mock TmapPedestrianPort tmapPort;
    @Mock WalkSegmentCachePort cache;

    WalkCoordResolver resolver;
    WalkSegmentEnricher enricher;

    @BeforeEach
    void setUp() {
        resolver = new WalkCoordResolver();
        enricher = new WalkSegmentEnricher(resolver, cache, tmapPort);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private SubPathResult walk(double sx, double sy, double ex, double ey,
                               String startName, String endName) {
        return new SubPathResult(
                3, 240, 320.0,
                Collections.emptyList(),
                Collections.emptyList(),
                startName, endName,
                sx, sy, ex, ey,
                null, null, null, null, null,
                null,
                null, null, null,
                null, null, null,
                null
        );
    }

    private SubPathResult subway(String startName, double sx, double sy,
                                 String endName, double ex, double ey,
                                 String startExitNo, Double startExitX, Double startExitY,
                                 String endExitNo, Double endExitX, Double endExitY) {
        return new SubPathResult(
                1, 1200, 6000.0,
                List.of(new LaneResult("2호선", null, 2, null)),
                Collections.emptyList(),
                startName, endName,
                sx, sy, ex, ey,
                null, null, null, null, null,
                null,
                startExitNo, startExitX, startExitY,
                endExitNo, endExitX, endExitY,
                null
        );
    }

    private RouteSearchResult resultOf(List<SubPathResult> subPaths) {
        PathInfo info = new PathInfo(2700, 1400, 640, 1, "출발", "도착", null);
        PathResult path = new PathResult(3, info, subPaths, Collections.emptyList());
        return new RouteSearchResult(0, 0, 1, 0, 0, List.of(path));
    }

    private WalkSegment sampleSegment() {
        return new WalkSegment(
                List.of(new CoordPoint(127.027, 37.497), new CoordPoint(127.028, 37.498)),
                320, 240,
                List.of(new WalkStep(0, "SP", 200, "이동", 127.027, 37.497))
        );
    }

    // ── 테스트 ────────────────────────────────────────────────────────────

    @Test
    void TC_도보_없는_path는_원본_그대로_반환() {
        SubPathResult sw = subway("강남", 127.0, 37.5, "교대", 127.01, 37.49,
                null, null, null, null, null, null);
        RouteSearchResult original = resultOf(List.of(sw));

        RouteSearchResult result = enricher.enrich(original,
                127.0, 37.5, "출발지",
                127.01, 37.49, "도착지");

        assertThat(result).isEqualTo(original);
        verifyNoInteractions(tmapPort, cache);
    }

    @Test
    void TC_도보_1구간_정상_보강() {
        SubPathResult firstWalk = walk(127.0, 37.5, 127.01, 37.49, "출발", "도착");
        RouteSearchResult original = resultOf(List.of(firstWalk));

        when(cache.get(any())).thenReturn(Optional.empty());
        when(tmapPort.search(any())).thenReturn(sampleSegment());

        RouteSearchResult result = enricher.enrich(original,
                127.0, 37.5, "출발지",
                127.01, 37.49, "도착지");

        SubPathResult enriched = result.paths().get(0).subPaths().get(0);
        assertThat(enriched.polyline()).hasSize(2);
        assertThat(enriched.walkSteps()).hasSize(1);
        assertThat(enriched.startName()).isEqualTo("출발지");
        assertThat(enriched.endName()).isEqualTo("도착지");
        verify(cache).put(any(), eq(sampleSegment()), eq(Duration.ofDays(7)));
    }

    @Test
    void TC_캐시_HIT_시_TMAP_미호출() {
        SubPathResult firstWalk = walk(127.0, 37.5, 127.01, 37.49, "출발", "도착");
        RouteSearchResult original = resultOf(List.of(firstWalk));

        when(cache.get(any())).thenReturn(Optional.of(sampleSegment()));

        RouteSearchResult result = enricher.enrich(original,
                127.0, 37.5, "출발지",
                127.01, 37.49, "도착지");

        assertThat(result.paths().get(0).subPaths().get(0).polyline()).hasSize(2);
        verifyNoInteractions(tmapPort);
        verify(cache, never()).put(any(), any(), any());
        verify(cache, never()).putNegative(any(), any());
    }

    @Test
    void TC_TMAP_빈_응답은_negative_cache_저장_후_원본_유지() {
        SubPathResult firstWalk = walk(127.0, 37.5, 127.01, 37.49, "출발", "도착");
        RouteSearchResult original = resultOf(List.of(firstWalk));

        when(cache.get(any())).thenReturn(Optional.empty());
        when(tmapPort.search(any())).thenReturn(WalkSegment.empty());

        RouteSearchResult result = enricher.enrich(original,
                127.0, 37.5, "출발지",
                127.01, 37.49, "도착지");

        // 빈 응답: polyline 비어있어야 하고 원본 그대로 반환
        SubPathResult unchanged = result.paths().get(0).subPaths().get(0);
        assertThat(unchanged.polyline()).isNull();
        verify(cache).putNegative(any(), eq(Duration.ofDays(1)));
    }

    @Test
    void TC_TMAP_예외_시_폴백_원본_유지() {
        SubPathResult firstWalk = walk(127.0, 37.5, 127.01, 37.49, "출발", "도착");
        RouteSearchResult original = resultOf(List.of(firstWalk));

        when(cache.get(any())).thenReturn(Optional.empty());
        when(tmapPort.search(any())).thenThrow(new TmapApiException(500, "5xx"));

        RouteSearchResult result = enricher.enrich(original,
                127.0, 37.5, "출발지",
                127.01, 37.49, "도착지");

        // 예외: polyline null + 캐시에 저장 안 함
        SubPathResult unchanged = result.paths().get(0).subPaths().get(0);
        assertThat(unchanged.polyline()).isNull();
        verify(cache, never()).put(any(), any(), any());
        verify(cache, never()).putNegative(any(), any());
    }

    @Test
    void TC_도보_N구간_병렬_호출_및_각각_보강() {
        SubPathResult w1 = walk(127.0, 37.5, 127.01, 37.49, "출발", "강남");
        SubPathResult sub = subway("강남", 127.01, 37.49, "교대", 127.02, 37.48,
                null, null, null, null, null, null);
        SubPathResult w2 = walk(127.02, 37.48, 127.03, 37.47, "교대", "도착");
        RouteSearchResult original = resultOf(List.of(w1, sub, w2));

        when(cache.get(any())).thenReturn(Optional.empty());
        when(tmapPort.search(any())).thenReturn(sampleSegment());

        RouteSearchResult result = enricher.enrich(original,
                127.0, 37.5, "출발지",
                127.03, 37.47, "도착지");

        List<SubPathResult> subs = result.paths().get(0).subPaths();
        assertThat(subs.get(0).polyline()).hasSize(2);
        assertThat(subs.get(1).polyline()).isNull(); // 지하철은 변경 없음
        assertThat(subs.get(2).polyline()).hasSize(2);
        // 두 도보 구간 모두 TMAP 호출
        verify(tmapPort, times(2)).search(any());
    }

    @Test
    void TC_같은_좌표쌍_dedupe_TMAP_1회만_호출() {
        // 두 도보 구간이 우연히 같은 출발/도착 좌표를 갖는 경우 (이론적)
        SubPathResult w1 = walk(127.0, 37.5, 127.01, 37.49, "A", "B");
        SubPathResult w2 = walk(127.0, 37.5, 127.01, 37.49, "A", "B");
        RouteSearchResult original = resultOf(List.of(w1, w2));

        when(cache.get(any())).thenReturn(Optional.empty());
        when(tmapPort.search(any())).thenReturn(sampleSegment());

        enricher.enrich(original,
                127.0, 37.5, "출발지",
                127.01, 37.49, "도착지");

        // 같은 cacheKey → futures map dedupe → TMAP 1회만 호출
        verify(tmapPort, times(1)).search(any());
    }

    @Test
    void TC_지하철_인접_도보는_출구_좌표와_출구번호_사용() {
        // 도보 → 지하철 (강남역 11번 출구)
        SubPathResult sub = subway("강남", 127.0, 37.5, "교대", 127.01, 37.49,
                "11", 127.005, 37.501, null, null, null);
        SubPathResult w1 = walk(127.0, 37.5, 127.0, 37.5, "출발", "강남");
        RouteSearchResult original = resultOf(List.of(w1, sub));

        when(cache.get(any())).thenReturn(Optional.empty());
        when(tmapPort.search(any())).thenReturn(sampleSegment());

        RouteSearchResult result = enricher.enrich(original,
                127.0, 37.5, "출발지",
                127.01, 37.49, "도착지");

        SubPathResult enriched = result.paths().get(0).subPaths().get(0);
        // end는 강남 11번 출구
        assertThat(enriched.endX()).isEqualTo(127.005);
        assertThat(enriched.endY()).isEqualTo(37.501);
        assertThat(enriched.endExitNo()).isEqualTo("11");
        assertThat(enriched.endName()).isEqualTo("강남 11번 출구");
        // start는 사용자 출발지
        assertThat(enriched.startExitNo()).isNull();
    }

    @Test
    void TC_출발과_도착_좌표_동일하면_TMAP_미호출_원본_유지() {
        // 사용자 시작/도착 좌표가 동일 (또는 caller가 같은 위치) → TMAP 호출 스킵
        SubPathResult sameLocWalk = walk(127.074919, 37.538831, 127.074919, 37.538831, "A", "A");
        RouteSearchResult original = resultOf(List.of(sameLocWalk));

        RouteSearchResult result = enricher.enrich(original,
                127.074919, 37.538831, "출발",
                127.074919, 37.538831, "도착");

        verifyNoInteractions(tmapPort);
        verifyNoInteractions(cache);
        // 원본 그대로 유지 (polyline=null)
        SubPathResult unchanged = result.paths().get(0).subPaths().get(0);
        assertThat(unchanged.polyline()).isNull();
    }

    @Test
    void TC_출발과_도착_좌표가_threshold_이내면_TMAP_미호출() {
        // 단일 도보 subPath: resolver는 요청 좌표를 그대로 사용.
        // 요청 시작/끝 좌표가 1e-4 (≈ 11m) 이내 → 동일 위치로 간주 → skip
        SubPathResult oneWalk = walk(127.074919, 37.538831, 127.074950, 37.538850, "A", "A");
        RouteSearchResult original = resultOf(List.of(oneWalk));

        enricher.enrich(original,
                127.074919, 37.538831, "출발",
                127.074950, 37.538850, "도착"); // 차이 3.1e-5, 1.9e-5

        verifyNoInteractions(tmapPort);
    }

    @Test
    void TC_같은_path_내_같은_위치_도보와_정상_도보_혼합() {
        // 첫 도보는 같은 위치(skip), 다음 도보는 정상 처리
        SubPathResult skipped = walk(127.0, 37.5, 127.0, 37.5, "A", "A");
        SubPathResult sub = subway("A", 127.0, 37.5, "B", 127.01, 37.49,
                null, null, null, null, null, null);
        SubPathResult realWalk = walk(127.01, 37.49, 127.02, 37.48, "B", "C");
        RouteSearchResult original = resultOf(List.of(skipped, sub, realWalk));

        when(cache.get(any())).thenReturn(Optional.empty());
        when(tmapPort.search(any())).thenReturn(sampleSegment());

        enricher.enrich(original,
                127.0, 37.5, "출발",
                127.02, 37.48, "도착");

        // 정상 도보 1구간만 TMAP 호출
        verify(tmapPort, times(1)).search(any());
    }

    @Test
    void TC_TMAP_요청에_resolved_name_전달() {
        SubPathResult sub = subway("강남", 127.0, 37.5, "교대", 127.01, 37.49,
                "11", 127.005, 37.501, null, null, null);
        SubPathResult w1 = walk(127.0, 37.5, 127.0, 37.5, "출발", "강남");
        RouteSearchResult original = resultOf(List.of(w1, sub));

        when(cache.get(any())).thenReturn(Optional.empty());
        when(tmapPort.search(any())).thenReturn(sampleSegment());

        enricher.enrich(original,
                127.0, 37.5, "출발지",
                127.01, 37.49, "도착지");

        // TMAP 요청에 "강남 11번 출구"가 endName으로 전달되어야 함
        var captor = org.mockito.ArgumentCaptor.forClass(WalkSearchCommand.class);
        verify(tmapPort).search(captor.capture());
        assertThat(captor.getValue().endName()).isEqualTo("강남 11번 출구");
        assertThat(captor.getValue().startName()).isEqualTo("출발지");
    }
}
