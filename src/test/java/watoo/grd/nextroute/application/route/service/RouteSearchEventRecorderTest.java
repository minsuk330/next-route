package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SearchMeta;
import watoo.grd.nextroute.domain.route.log.repository.RouteSearchEventRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RouteSearchEventRecorderTest {

    private static final Instant NOW = Instant.parse("2026-11-26T06:00:00Z");
    private final RouteSearchEventRepository repository = mock(RouteSearchEventRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private RouteSearchEventRecorder recorder(TaskExecutor executor) {
        return new RouteSearchEventRecorder(repository, executor, clock);
    }

    private static RouteSearchResult result(String sessionId, String outcome, int paths) {
        PathInfo info = new PathInfo(30, 1250, 300, 1, "강남", "판교", "mapObj");
        List<PathResult> list = java.util.stream.IntStream.range(0, paths)
                .mapToObj(i -> new PathResult(1, info, List.of(), List.of())).toList();
        RouteSearchResult r = new RouteSearchResult(2, 0, 1, 0, 0, list);
        if (outcome != null) {
            r = r.withMeta(new SearchMeta(outcome, List.of()));
        }
        return sessionId == null ? r : r.withSnapshot(sessionId, NOW.plusSeconds(600));
    }

    @Test
    @DisplayName("스냅샷 id·결과·경로 수·지연(ms)을 남긴다")
    void recordsSearch() {
        recorder(new SyncTaskExecutor()).record(42L, result("sess-1", "full", 2), 1_234_000_000L);

        verify(repository).insert("sess-1", 42L, NOW, "full", 2, 1234);
    }

    @Test
    @DisplayName("익명·스냅샷 없음(경로 0개)도 남긴다")
    void recordsAnonymousWithoutSnapshot() {
        recorder(new SyncTaskExecutor()).record(null, result(null, null, 0), 5_000_000L);

        verify(repository).insert(null, null, NOW, null, 0, 5);
    }

    @Test
    @DisplayName("outcome 은 20자로 자른다")
    void truncatesOutcome() {
        assertThat(RouteSearchEventRecorder.outcome(result(null, "x".repeat(30), 0))).hasSize(20);
    }

    @Test
    @DisplayName("큐가 차도 검색에 예외를 던지지 않는다")
    void rejectedIsSwallowed() {
        TaskExecutor full = task -> { throw new TaskRejectedException("full"); };

        assertThatCode(() -> recorder(full).record(1L, result("s", "full", 1), 0)).doesNotThrowAnyException();
        verify(repository, never()).insert(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("저장이 실패해도 예외를 밖으로 내지 않는다")
    void saveFailureIsSwallowed() {
        given(repository.insert(any(), any(), any(), any(), anyInt(), anyInt()))
                .willThrow(new IllegalStateException("db down"));

        assertThatCode(() -> recorder(new SyncTaskExecutor()).record(1L, result("s", "full", 1), 0))
                .doesNotThrowAnyException();
    }
}
