package watoo.grd.nextroute.infrastructure.adapter.in.api.subway;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayStationArrivalsUseCase;

@RestController
@RequestMapping("/api/subway/stations")
@RequiredArgsConstructor
@Tag(name = "[공용] 지하철 도착 정보")
public class SubwayStationController {

    private final GetSubwayStationArrivalsUseCase getSubwayStationArrivalsUseCase;

    @GetMapping("/{stationId}/arrivals")
    @Operation(summary = "역 ID 로 곧 오는 열차를 방향별로 조회",
            description = "stationId 는 /api/nearby/subway-stations 의 stationId. 없는 역은 404, 운행 정보가 없으면 directions 가 빈 목록")
    public ResponseEntity<SubwayStationArrivalsResponse> getArrivals(@PathVariable String stationId) {
        return ResponseEntity.ok(getSubwayStationArrivalsUseCase.getArrivals(stationId));
    }
}
