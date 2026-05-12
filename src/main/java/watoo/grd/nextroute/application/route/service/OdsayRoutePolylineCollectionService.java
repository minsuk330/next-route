package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.domain.route.polyline.entity.OdsayRoutePolylineCollectionJob;
import watoo.grd.nextroute.domain.route.polyline.service.OdsayRoutePolylineDataService;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OdsayRoutePolylineCollectionService {

    private static final int BATCH_LIMIT = 5;

    private final OdsayRoutePolylineDataService dataService;
    private final OdsayRoutePolylineLoadService loadService;

    public int processPendingJobs() {
        List<OdsayRoutePolylineCollectionJob> pending = dataService.findPendingJobs(BATCH_LIMIT);
        if (pending.isEmpty()) return 0;

        int processed = 0;
        for (OdsayRoutePolylineCollectionJob job : pending) {
            if (!dataService.markJobRunning(job.getId())) continue;

            try {
                OdsayRoutePolylineLoadService.LoadResult result =
                        loadService.load(job.getOdsayRouteId(), job.getLaneClass());

                if (result.loaded()) {
                    dataService.markJobSuccess(job.getId());
                    log.info("[Collection] SUCCESS routeId={} laneClass={} points={}",
                            result.routeId(), result.laneClass(), result.pointCount());
                } else {
                    dataService.markJobFailed(job.getId(), result.errorMessage());
                    log.warn("[Collection] FAILED routeId={} laneClass={}: {}",
                            result.routeId(), result.laneClass(), result.errorMessage());
                }
                processed++;
            } catch (Exception e) {
                dataService.markJobFailed(job.getId(), e.getMessage());
                log.error("[Collection] Exception for routeId={}: {}", job.getOdsayRouteId(), e.getMessage(), e);
            }
        }
        return processed;
    }

    public void retryJob(Long jobId) {
        dataService.retryJob(jobId);
    }
}
