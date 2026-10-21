package watoo.grd.nextroute.infrastructure.adapter.in.api.subway;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationsAheadResponse;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayStationArrivalsUseCase;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayStationsAheadUseCase;

@RestController
@RequestMapping("/api/subway/stations")
@RequiredArgsConstructor
@Tag(name = "[공용] 지하철 도착 정보")
public class SubwayStationController {

    private final GetSubwayStationArrivalsUseCase getSubwayStationArrivalsUseCase;
    private final GetSubwayStationsAheadUseCase getSubwayStationsAheadUseCase;

    @GetMapping("/{stationId}/arrivals")
    @Operation(summary = "역 ID 로 곧 오는 열차를 방향별로 조회",
            description = "stationId 는 /api/nearby/subway-stations 의 stationId. 없는 역은 404, 운행 정보가 없으면 directions 가 빈 목록")
    public ResponseEntity<SubwayStationArrivalsResponse> getArrivals(@PathVariable String stationId) {
        return ResponseEntity.ok(getSubwayStationArrivalsUseCase.getArrivals(stationId));
    }

    @GetMapping("/{stationId}/stations-ahead")
    @Operation(summary = "승차역부터 한 방향으로 남은 역 (하차역 선택)",
            description = "direction 은 도착 API 의 direction(상행·하행·내선·외선). destination(탄 열차 행선지)을 주면 "
                    + "분기 노선에서 그 갈래 끝까지 준다. 분기점에서 멈추면 endReason=BRANCH 와 branches 를 준다. "
                    + "trainNo(탄 열차 번호)를 주면 train{kind,label,serviceId,stopsAtBoard} 와 역마다 stopsHere·"
                    + "expressStopsAway(급행이 서는 역만 센 수)를 채운다. stopsAway 는 완행 기준 그대로다. "
                    + "없는 역 404, 방향 400, 운행 정보에 없는 역 422, 노선 정보 일시 불가 503")
    public ResponseEntity<SubwayStationsAheadResponse> getStationsAhead(
            @PathVariable String stationId,
            @RequestParam String direction,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) String trainNo) {
        return ResponseEntity.ok(getSubwayStationsAheadUseCase.getStationsAhead(stationId, direction, destination,
                trainNo));
    }
}
