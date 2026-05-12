package watoo.grd.nextroute.infrastructure.adapter.in.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import watoo.grd.nextroute.application.route.service.OdsayRoutePolylineCollectionService;
import watoo.grd.nextroute.application.route.service.OdsayRoutePolylineLoadService;
import watoo.grd.nextroute.application.route.service.OdsayRoutePolylineLoadService.LoadResult;
import watoo.grd.nextroute.domain.route.polyline.entity.OdsayRoutePolyline;
import watoo.grd.nextroute.domain.route.polyline.entity.OdsayRoutePolylineCollectionJob;
import watoo.grd.nextroute.domain.route.polyline.service.OdsayRoutePolylineDataService;

import java.util.List;

@RestController
@RequestMapping("/api/admin/odsay-route-polylines")
@RequiredArgsConstructor
@Tag(name = "[어드민] ODsay 노선 폴리라인 관리")
public class OdsayRoutePolylineAdminController {

    private final OdsayRoutePolylineLoadService loadService;
    private final OdsayRoutePolylineCollectionService collectionService;
    private final OdsayRoutePolylineDataService dataService;

    @PostMapping("/{routeId}/load")
    @Operation(summary = "routeId 단건 폴리라인 적재")
    public ResponseEntity<LoadResult> loadOne(
            @PathVariable String routeId,
            @RequestParam(defaultValue = "2") int laneClass) {
        return ResponseEntity.ok(loadService.load(routeId, laneClass));
    }

    @PostMapping("/load-all")
    @Operation(summary = "seed 기반 전체 폴리라인 일괄 적재")
    public ResponseEntity<List<LoadResult>> loadAll() {
        return ResponseEntity.ok(loadService.loadAll());
    }

    @GetMapping
    @Operation(summary = "적재된 폴리라인 목록 조회")
    public ResponseEntity<List<PolylineSummary>> listPolylines() {
        List<PolylineSummary> summaries = dataService.findAllPolylines().stream()
                .map(p -> new PolylineSummary(
                        p.getId(), p.getOdsayRouteId(), p.getLaneClass(),
                        p.getPointCount(), p.getSourceMapObject(),
                        p.getFetchedAt().toString(), p.getUpdatedAt().toString()))
                .toList();
        return ResponseEntity.ok(summaries);
    }

    @GetMapping("/jobs")
    @Operation(summary = "collection job 목록 조회")
    public ResponseEntity<List<OdsayRoutePolylineCollectionJob>> listJobs() {
        return ResponseEntity.ok(dataService.findAllJobs());
    }

    @PostMapping("/jobs/{jobId}/retry")
    @Operation(summary = "FAILED job 재시도 등록")
    public ResponseEntity<Void> retryJob(@PathVariable Long jobId) {
        collectionService.retryJob(jobId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/jobs/process")
    @Operation(summary = "PENDING job 즉시 처리 (관리자 수동 트리거)")
    public ResponseEntity<ProcessResult> processJobs() {
        int count = collectionService.processPendingJobs();
        return ResponseEntity.ok(new ProcessResult(count));
    }

    public record PolylineSummary(
            Long id, String odsayRouteId, int laneClass,
            int pointCount, String sourceMapObject,
            String fetchedAt, String updatedAt) {}

    public record ProcessResult(int processed) {}
}
