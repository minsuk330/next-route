package watoo.grd.nextroute.application.route.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.domain.route.log.repository.RouteSearchEventRepository;

import java.time.Clock;
import java.time.Instant;

/**
 * 경로 검색 한 번을 {@code route_search_event} 에 남긴다(사용 퍼널 첫 단계, V79).
 *
 * <p>요청 스레드는 막지 않는다: 별도 executor 로 INSERT 를 넘기고, 큐가 차거나 저장이 실패하면 WARN 만 남기고 버린다.
 * 통계 기록 때문에 검색이 느려지거나 실패하면 안 된다. 익명 검색도 남긴다({@code userId} null).
 *
 * <p>{@code searchSessionId} 는 응답의 스냅샷 id 다. 앱이 여정 시작 때 같은 값을 보내 여정과 이어진다.
 */
@Slf4j
public class RouteSearchEventRecorder {

    static final int OUTCOME_MAX = 20;

    private final RouteSearchEventRepository repository;
    private final TaskExecutor executor;
    private final Clock clock;

    public RouteSearchEventRecorder(RouteSearchEventRepository repository, TaskExecutor executor, Clock clock) {
        this.repository = repository;
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * @param latencyNanos 검색 시작부터 응답 직전(스냅샷 저장 포함)까지
     */
    public void record(Long userId, RouteSearchResult result, long latencyNanos) {
        if (result == null) {
            return;
        }
        Instant searchedAt = clock.instant();
        String sessionId = result.searchSessionId();
        String outcome = outcome(result);
        int pathCount = result.paths() == null ? 0 : result.paths().size();
        int latencyMs = (int) Math.min(Integer.MAX_VALUE, Math.max(0, latencyNanos / 1_000_000));
        try {
            executor.execute(() -> save(sessionId, userId, searchedAt, outcome, pathCount, latencyMs));
        } catch (TaskRejectedException e) {
            log.warn("[SearchEvent] 큐가 가득 차 이번 기록을 건너뜀");
        }
    }

    private void save(String sessionId, Long userId, Instant searchedAt, String outcome, int pathCount, int latencyMs) {
        try {
            repository.insert(sessionId, userId, searchedAt, outcome, pathCount, latencyMs);
        } catch (RuntimeException e) {
            log.warn("[SearchEvent] 기록 실패(버림): {}", e.toString());
        }
    }

    static String outcome(RouteSearchResult result) {
        if (result.meta() == null || result.meta().outcome() == null) {
            return null;
        }
        String o = result.meta().outcome();
        return o.length() > OUTCOME_MAX ? o.substring(0, OUTCOME_MAX) : o;
    }
}
