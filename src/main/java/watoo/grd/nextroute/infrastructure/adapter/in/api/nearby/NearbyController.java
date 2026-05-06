package watoo.grd.nextroute.infrastructure.adapter.in.api.nearby;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import watoo.grd.nextroute.application.nearby.dto.NearbyBusStopResult;
import watoo.grd.nextroute.application.nearby.dto.NearbySubwayStationResult;
import watoo.grd.nextroute.application.nearby.port.in.GetNearbyBusStopsUseCase;
import watoo.grd.nextroute.application.nearby.port.in.GetNearbySubwayStationsUseCase;

import java.util.List;

@RestController
@RequestMapping("/api/nearby")
@RequiredArgsConstructor
@Validated
@Tag(name = "[공용] 주변 정류소/역 탐색")
public class NearbyController {

    private final GetNearbyBusStopsUseCase getNearbyBusStopsUseCase;
    private final GetNearbySubwayStationsUseCase getNearbySubwayStationsUseCase;

    @GetMapping("/bus-stops")
    @Operation(summary = "좌표 기반 주변 버스 정류장 조회 (500m~3km 자동 확장)")
    public ResponseEntity<List<NearbyBusStopResult>> getNearbyBusStops(
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") Double lng,
            @RequestParam(defaultValue = "0") int limit) {
        return ResponseEntity.ok(getNearbyBusStopsUseCase.getNearbyBusStops(lat, lng, limit));
    }

    @GetMapping("/subway-stations")
    @Operation(summary = "좌표 기반 주변 지하철역 조회 (500m~3km 자동 확장)")
    public ResponseEntity<List<NearbySubwayStationResult>> getNearbySubwayStations(
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") Double lng,
            @RequestParam(defaultValue = "0") int limit) {
        return ResponseEntity.ok(getNearbySubwayStationsUseCase.getNearbySubwayStations(lat, lng, limit));
    }
}
