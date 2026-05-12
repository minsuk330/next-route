package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.service.OdsayRoutePolylineCollectionService;

@Slf4j
@Component
@RequiredArgsConstructor
public class OdsayRoutePolylineCollectionScheduler {

    private final OdsayRoutePolylineCollectionService collectionService;

    @Scheduled(fixedDelay = 60_000)
    public void processJobs() {
        try {
            int processed = collectionService.processPendingJobs();
            if (processed > 0) {
                log.info("[CollectionScheduler] Processed {} job(s)", processed);
            }
        } catch (Exception e) {
            log.error("[CollectionScheduler] Unexpected error: {}", e.getMessage(), e);
        }
    }
}
