package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.route.config.RouteSearchHistoryProperties;
import watoo.grd.nextroute.application.route.dto.SearchHistoryEntry;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.SearchHistoryCommandPort;

/**
 * 워커 스레드에서 실제 저장을 수행한다. best-effort — 저장 실패(제약위반·DB 오류·timeout)는
 * 로그 + drop metric 으로 흡수하고 삼킨다. 워커 스레드에서 예외가 밖으로 나가도 응답과는 무관하지만,
 * 여기서 잡아 관측 가능한 drop 으로 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchHistoryAsyncWriter {

    private final SearchHistoryCommandPort commandPort;
    private final RouteSearchHistoryProperties props;
    private final RouteSearchMetrics metrics;

    public void write(SearchHistoryEntry entry) {
        try {
            commandPort.upsertAndTrim(entry, props.getMaxPerUser());
        } catch (Exception e) {
            metrics.recordHistoryDropped();
            log.warn("[SearchHistory] 저장 실패 — drop userId={}: {}", entry.userId(), e.getMessage());
        }
    }
}
