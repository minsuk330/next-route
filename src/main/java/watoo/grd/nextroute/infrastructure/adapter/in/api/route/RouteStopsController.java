package watoo.grd.nextroute.infrastructure.adapter.in.api.route;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.stopselection.dto.RouteStopsResult;
import watoo.grd.nextroute.application.stopselection.port.in.GetRouteStopsUseCase;

@RestController
@RequestMapping("/api/routes")
@RequiredArgsConstructor
@Tag(name = "[공용] 정류장 선택 UI")
public class RouteStopsController {

    private final GetRouteStopsUseCase getRouteStopsUseCase;

    @GetMapping("/{routeId}/stops")
    @Operation(summary = "노선 경유 정류장 목록 (지도 핀용 좌표 포함)")
    public ResponseEntity<RouteStopsResult> getRouteStops(@PathVariable String routeId) {
        return ResponseEntity.ok(getRouteStopsUseCase.getRouteStops(routeId));
    }
}
