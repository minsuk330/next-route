package watoo.grd.nextroute.infrastructure.adapter.in.api.stops;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.stopselection.dto.StopRouteResult;
import watoo.grd.nextroute.application.stopselection.port.in.GetStopRoutesUseCase;

import java.util.List;

@RestController
@RequestMapping("/api/stops")
@RequiredArgsConstructor
@Tag(name = "[공용] 정류장 선택 UI")
public class StopRoutesController {

    private final GetStopRoutesUseCase getStopRoutesUseCase;

    @GetMapping("/{stopId}/routes")
    @Operation(summary = "정류장 경유 버스 노선 목록 (방향/예측지원 배지 포함)")
    public ResponseEntity<List<StopRouteResult>> getStopRoutes(@PathVariable String stopId) {
        return ResponseEntity.ok(getStopRoutesUseCase.getStopRoutes(stopId));
    }
}
