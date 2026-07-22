package watoo.grd.nextroute.infrastructure.adapter.in.api.nearby;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.nearby.dto.BusStopResolveResult;
import watoo.grd.nextroute.application.nearby.port.in.ResolveBusStopUseCase;

@RestController
@RequestMapping("/api/bus-stops")
@RequiredArgsConstructor
@Validated
@Tag(name = "[공용] 버스 정류장 선택")
public class BusStopResolveController {

    private final ResolveBusStopUseCase resolveBusStopUseCase;

    @GetMapping("/resolve")
    @Operation(summary = "네이버 지도 심벌 좌표와 이름으로 내부 버스 정류장 식별")
    public ResponseEntity<BusStopResolveResult> resolve(
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") Double lng,
            @RequestParam(required = false, defaultValue = "") String name) {
        return ResponseEntity.ok(resolveBusStopUseCase.resolveBusStop(lat, lng, name));
    }
}
