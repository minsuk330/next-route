package watoo.grd.nextroute.infrastructure.adapter.in.api.admin;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.subway.service.SubwayCoordinateEnrichService;
import watoo.grd.nextroute.application.subway.service.SubwayCoordinateEnrichService.EnrichResult;

@RestController
@RequestMapping("/api/admin/subway")
@RequiredArgsConstructor
@Tag(name = "[어드민] 지하철 관리")
public class SubwayAdminController {

    private final SubwayCoordinateEnrichService subwayCoordinateEnrichService;

    @PostMapping("/enrich-coordinates")
    public ResponseEntity<EnrichResult> enrichCoordinates() {
        return ResponseEntity.ok(subwayCoordinateEnrichService.enrich());
    }
}
