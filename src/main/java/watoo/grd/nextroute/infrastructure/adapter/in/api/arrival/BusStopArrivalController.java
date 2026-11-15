package watoo.grd.nextroute.infrastructure.adapter.in.api.arrival;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.arrival.dto.BusStopArrivalResponse;
import watoo.grd.nextroute.application.arrival.dto.StopArrivals;
import watoo.grd.nextroute.application.arrival.port.in.GetBusStopArrivalsUseCase;

/**
 * 정류장 단위 버스 도착(#577). 정류장 하나에 서는 노선 전부를 한 번에 주고, 정류장 여러 곳(최대 10)도 한 번에 받는다.
 * {@code /api/arrivals/bus/stops/{id}} 는 기존 {@code /api/arrivals/bus/{stopId}/{routeId}} 에 걸려 쓰지 않는다.
 */
@RestController
@RequestMapping("/api/bus/stops")
@RequiredArgsConstructor
@Tag(name = "[공용] 버스 도착 정보")
public class BusStopArrivalController {

    private final GetBusStopArrivalsUseCase useCase;

    @GetMapping("/{stopId}/arrivals")
    @Operation(summary = "정류장 하나의 노선별 도착 (서울·경기·TAGO). 모르는 정류장, 조회 실패는 빈 배열")
    public ResponseEntity<List<BusStopArrivalResponse>> getStopArrivals(@PathVariable String stopId) {
        return ResponseEntity.ok(useCase.getStopArrivals(stopId));
    }

    @GetMapping("/arrivals")
    @Operation(summary = "정류장 여러 곳(쉼표, 최대 10)의 노선별 도착. 요청 순서대로, 정류장마다 status")
    public ResponseEntity<List<StopArrivals>> getStopsArrivals(@RequestParam("stopIds") String stopIds) {
        List<String> ids = Arrays.asList(stopIds.split(","));
        if (BusStopArrivalQueryHelper.distinctCount(ids) > GetBusStopArrivalsUseCase.MAX_STOPS) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(useCase.getStopsArrivals(ids));
    }
}
