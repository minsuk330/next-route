package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.regionalbus.config.RegionalBusCatalogProperties;
import watoo.grd.nextroute.application.regionalbus.service.RegionalBusCatalogLoadService;

/**
 * 경기·TAGO 노선 정류장 표 적재(#267). 월 1회 + 표가 비어 있을 때 부팅 직후 1회.
 *
 * <p>한 번에 약 2.15만 회를 부른다. 매 배포마다 다시 돌지 않도록 부팅 적재는 표가 비어 있을 때만 한다.
 * 부팅을 막지 않게 별도 스레드에서 돈다.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "collector.regional-bus-catalog", name = "enabled", havingValue = "true")
public class RegionalBusCatalogScheduler {

    private final RegionalBusCatalogLoadService loadService;
    private final RegionalBusCatalogProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!properties.isLoadOnEmpty()) {
            return;
        }
        boolean empty;
        boolean needsStopNumbers;
        try {
            empty = loadService.isEmpty();
            needsStopNumbers = !empty && loadService.needsStopNumbers();
        } catch (RuntimeException e) {
            log.warn("[RegionalBusCatalog] table check failed (V56/V64 missing?): {}", e.getMessage());
            return;
        }
        if (empty || needsStopNumbers) {
            log.info("[RegionalBusCatalog] {}; initial load starting",
                    empty ? "table empty" : "stop numbers missing (V64)");
            Thread thread = new Thread(loadService::load, "regional-bus-catalog-initial-load");
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Scheduled(cron = "${collector.regional-bus-catalog.cron}", zone = "Asia/Seoul")
    public void monthlyLoad() {
        log.info("[RegionalBusCatalog] monthly load starting");
        loadService.load();
    }
}
