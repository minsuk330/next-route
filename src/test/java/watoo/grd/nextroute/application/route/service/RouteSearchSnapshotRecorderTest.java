package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.route.config.RouteSnapshotProperties;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SearchMeta;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 스냅샷 부착의 계약을 고정한다.
 *
 * <p>가장 중요한 건 실패 경로다 — 저장소가 죽어도 검색 결과는 그대로 나가야 하고,
 * 클라이언트는 {@code meta.degraded} 로만 그 사실을 안다(PLAN §4.3).
 */
@ExtendWith(MockitoExtension.class)
class RouteSearchSnapshotRecorderTest {

    private static final Instant NOW = Instant.parse("2026-07-28T10:40:00Z");

    @Mock RouteSearchSnapshotPort snapshotPort;

    private RouteSnapshotProperties properties;
    private RouteSearchSnapshotRecorder recorder;

    @BeforeEach
    void setUp() {
        properties = new RouteSnapshotProperties();
        properties.setEnabled(true);
        properties.setTtlSeconds(600);
        recorder = new RouteSearchSnapshotRecorder(
                snapshotPort, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ── 성공 경로 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("저장에 성공하면 searchSessionId·expiresAt·pathKey 가 채워진다")
    void attachFillsSnapshotFields() {
        when(snapshotPort.save(eq(7L), any(), anyBoolean(), any())).thenReturn("session-1");

        RouteSearchResult attached = recorder.attach(7L, result(2), false);

        assertThat(attached.searchSessionId()).isEqualTo("session-1");
        assertThat(attached.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(attached.paths()).extracting(PathResult::pathKey).containsExactly("0", "1");
        // 기존 응답 필드는 그대로다.
        assertThat(attached.searchType()).isEqualTo(0);
        assertThat(attached.meta().outcome()).isEqualTo("full");
        assertThat(attached.meta().degraded()).isEmpty();
    }

    @Test
    @DisplayName("저장되는 값에도 pathKey 가 이미 채워져 있다 — 저장본과 응답본의 키가 같다")
    void savedSnapshotAlreadyHasPathKeys() {
        when(snapshotPort.save(any(), any(), anyBoolean(), any())).thenReturn("session-1");

        recorder.attach(null, result(2), false);

        var captor = org.mockito.ArgumentCaptor.forClass(RouteSearchResult.class);
        verify(snapshotPort).save(isNull(), captor.capture(), eq(false), eq(NOW.plusSeconds(600)));
        assertThat(captor.getValue().paths())
                .extracting(PathResult::pathKey).containsExactly("0", "1");
    }

    @Test
    @DisplayName("급행 끄기 요청값을 저장소에 넘긴다 — 결과 meta 가 필터 해제(express_filter)를 말해도 그대로(subway-express S5)")
    void excludeExpressPassedThroughEvenWhenRelaxed() {
        when(snapshotPort.save(any(), any(), anyBoolean(), any())).thenReturn("session-1");
        SearchMeta relaxed = new SearchMeta("partial",
                List.of(new SearchMeta.DegradedComponent("express_filter", "empty_result")));

        recorder.attach(7L, result(2).withMeta(relaxed), true);

        verify(snapshotPort).save(eq(7L), any(), eq(true), eq(NOW.plusSeconds(600)));
    }

    // ── 실패 경로 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("저장소가 예외를 던져도 원본 결과를 그대로 반환하고 degraded 로만 알린다")
    void portExceptionKeepsOriginalResult() {
        when(snapshotPort.save(any(), any(), anyBoolean(), any()))
                .thenThrow(new RuntimeException("redis down"));

        RouteSearchResult attached = recorder.attach(7L, result(2), false);

        assertThat(attached.searchSessionId()).isNull();
        assertThat(attached.expiresAt()).isNull();
        assertThat(attached.paths()).extracting(PathResult::pathKey).containsOnlyNulls();
        assertThat(attached.paths()).hasSize(2);
        assertThat(attached.meta().degraded())
                .containsExactly(new SearchMeta.DegradedComponent("journey_snapshot", "upstream_error"));
    }

    @Test
    @DisplayName("저장 실패(null 반환)도 같은 degraded 표시로 드러난다")
    void nullSessionIdIsDegraded() {
        when(snapshotPort.save(any(), any(), anyBoolean(), any())).thenReturn(null);

        RouteSearchResult attached = recorder.attach(7L, result(2), false);

        assertThat(attached.searchSessionId()).isNull();
        assertThat(attached.meta().degraded())
                .containsExactly(new SearchMeta.DegradedComponent("journey_snapshot", "upstream_error"));
    }

    @Test
    @DisplayName("기존 degraded 항목은 보존한 채 journey_snapshot 을 덧붙인다")
    void existingDegradedEntriesArePreserved() {
        when(snapshotPort.save(any(), any(), anyBoolean(), any())).thenReturn(null);
        SearchMeta meta = new SearchMeta("partial",
                List.of(new SearchMeta.DegradedComponent("walk", "timeout")));

        RouteSearchResult attached = recorder.attach(7L, result(2).withMeta(meta), false);

        assertThat(attached.meta().outcome()).isEqualTo("partial");
        assertThat(attached.meta().degraded()).containsExactly(
                new SearchMeta.DegradedComponent("walk", "timeout"),
                new SearchMeta.DegradedComponent("journey_snapshot", "upstream_error"));
    }

    // ── 생략 경로 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("enabled=false 면 저장을 시도하지 않고 결과를 그대로 통과시킨다")
    void disabledSkipsSaveEntirely() {
        properties.setEnabled(false);
        RouteSearchResult original = result(2);

        RouteSearchResult attached = recorder.attach(7L, original, false);

        assertThat(attached).isSameAs(original);
        assertThat(attached.searchSessionId()).isNull();
        assertThat(attached.meta().degraded()).isEmpty();   // 실패가 아니므로 degraded 도 아니다
        verifyNoInteractions(snapshotPort);
    }

    @Test
    @DisplayName("경로가 없으면 저장하지 않는다 — 스냅샷으로 시작할 여정이 없다")
    void emptyPathsSkipsSave() {
        RouteSearchResult original = new RouteSearchResult(0, 0, 0, 0, 0, List.of())
                .withMeta(new SearchMeta("base_only", List.of()));

        RouteSearchResult attached = recorder.attach(7L, original, false);

        assertThat(attached).isSameAs(original);
        verifyNoInteractions(snapshotPort);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private static RouteSearchResult result(int pathCount) {
        List<PathResult> paths = java.util.stream.IntStream.range(0, pathCount)
                .mapToObj(i -> new PathResult(1, info(30 + i), List.of(), List.of()))
                .toList();
        return new RouteSearchResult(0, 0, 1, 0, 0, paths)
                .withMeta(new SearchMeta("full", List.of()));
    }

    private static PathInfo info(int totalTime) {
        return new PathInfo(totalTime, 1250, 300, 1, "강남", "판교", "mapObj");
    }
}
