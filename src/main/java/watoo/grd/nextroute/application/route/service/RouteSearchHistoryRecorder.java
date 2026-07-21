package watoo.grd.nextroute.application.route.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SearchHistoryEntry;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.in.RecordSearchHistoryUseCase;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 검색 이력 기록의 진입점. entry 로 변환 후 전용 executor 에 저장을 submit 하고 즉시 반환한다.
 * 큐 포화({@link RejectedExecutionException})면 drop metric 만 남기고 응답엔 영향 0.
 */
@Slf4j
@Service
public class RouteSearchHistoryRecorder implements RecordSearchHistoryUseCase {

    private final Executor executor;
    private final SearchHistoryAsyncWriter writer;
    private final RouteSearchMetrics metrics;
    private final Clock clock;

    public RouteSearchHistoryRecorder(
            @Qualifier("searchHistoryExecutor") Executor executor,
            SearchHistoryAsyncWriter writer,
            RouteSearchMetrics metrics,
            Clock clock) {
        this.executor = executor;
        this.writer = writer;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public void record(Long userId, RouteSearchRequest request, RouteSearchResult result) {
        SearchHistoryEntry entry = SearchHistoryEntry.of(
                userId, request, result, LocalDateTime.now(clock));
        try {
            executor.execute(() -> writer.write(entry));
        } catch (RejectedExecutionException e) {
            metrics.recordHistoryDropped();
            log.debug("[SearchHistory] 저장 큐 포화 — drop userId={}", userId);
        }
    }
}
