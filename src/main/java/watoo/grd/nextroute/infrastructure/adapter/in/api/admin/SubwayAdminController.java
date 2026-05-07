package watoo.grd.nextroute.infrastructure.adapter.in.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.subway.service.SubwayArrivalEventBatchScheduler;
import watoo.grd.nextroute.application.subway.service.SubwayArrivalEventBatchScheduler.BatchRunResult;
import watoo.grd.nextroute.application.subway.service.SubwayCoordinateEnrichService;
import watoo.grd.nextroute.application.subway.service.SubwayCoordinateEnrichService.EnrichResult;

@RestController
@RequestMapping("/api/admin/subway")
@RequiredArgsConstructor
@Tag(name = "[어드민] 지하철 관리")
public class SubwayAdminController {

    private final SubwayCoordinateEnrichService subwayCoordinateEnrichService;
    private final SubwayArrivalEventBatchScheduler batchScheduler;

    @PostMapping("/enrich-coordinates")
    public ResponseEntity<EnrichResult> enrichCoordinates() {
        return ResponseEntity.ok(subwayCoordinateEnrichService.enrich());
    }

    @PostMapping("/batch/arrival-event/trigger")
    @Operation(summary = "배치 수동 트리거 (개발용)", description = "date 미지정 시 어제 날짜로 실행")
    public ResponseEntity<BatchRunResult> triggerBatch(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate serviceDate = (date != null)
                ? date
                : LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);

        return ResponseEntity.ok(batchScheduler.runForDate(serviceDate));
    }
}
