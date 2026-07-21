package watoo.grd.nextroute.application.route.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SearchHistoryEntry;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RouteSearchHistoryRecorderTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW =
            LocalDateTime.ofInstant(Instant.parse("2026-07-21T00:00:00Z"), KST);

    @Mock SearchHistoryAsyncWriter writer;

    SimpleMeterRegistry registry;
    RouteSearchMetrics metrics;
    Clock clock;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new RouteSearchMetrics(registry);
        clock = Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), KST);
    }

    private RouteSearchRequest request() {
        return new RouteSearchRequest(127.0, 37.5, 127.01, 37.51, "출발지", "도착지");
    }

    private RouteSearchResult result() {
        return new RouteSearchResult(2, 0, 0, 0, 0, List.of());
    }

    @Test
    void TC_정상_submit시_워커로_변환된_entry가_전달된다() {
        // 동기 실행 executor 로 submit 된 작업을 즉시 돌린다.
        Executor direct = Runnable::run;
        RouteSearchHistoryRecorder recorder =
                new RouteSearchHistoryRecorder(direct, writer, metrics, clock);

        recorder.record(7L, request(), result());

        ArgumentCaptor<SearchHistoryEntry> captor = ArgumentCaptor.forClass(SearchHistoryEntry.class);
        verify(writer).write(captor.capture());
        SearchHistoryEntry entry = captor.getValue();
        assertThat(entry.userId()).isEqualTo(7L);
        assertThat(entry.startX()).isEqualTo(127.0);
        assertThat(entry.endY()).isEqualTo(37.51);
        assertThat(entry.startName()).isEqualTo("출발지");
        assertThat(entry.searchType()).isEqualTo(2);
        assertThat(entry.pathCount()).isZero();
        assertThat(entry.searchedAt()).isEqualTo(NOW);
    }

    @Test
    void TC_큐_포화시_drop_metric만_남기고_삼킨다() {
        Executor saturated = mock(Executor.class);
        doThrow(new RejectedExecutionException("queue full")).when(saturated).execute(any());
        RouteSearchHistoryRecorder recorder =
                new RouteSearchHistoryRecorder(saturated, writer, metrics, clock);

        recorder.record(7L, request(), result());

        verifyNoInteractions(writer);
        assertThat(registry.counter(RouteSearchMetrics.HISTORY_DROPPED).count())
                .isEqualTo(1.0);
    }
}
